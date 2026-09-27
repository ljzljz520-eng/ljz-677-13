package com.excel.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.config.ReportQueueProperties;
import com.excel.dto.CreateReportTaskRequest;
import com.excel.dto.ReportStatusCountDTO;
import com.excel.dto.mock.NationalReportRequest;
import com.excel.dto.mock.NationalReportResponse;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportRecord;
import com.excel.entity.ReportBatch;
import com.excel.entity.ReportTask;
import com.excel.entity.User;
import com.excel.mapper.ExcelDataMapper;
import com.excel.mapper.ImportRecordMapper;
import com.excel.mapper.ReportBatchMapper;
import com.excel.mapper.ReportTaskMapper;
import com.excel.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 上送队列服务。
 *
 * 设计要点：
 * 1. 任务(ReportTask)与批次(ReportBatch)持久化到数据库，页面通过查询接口获取进度，不占用HTTP连接；
 * 2. 内存使用单线程调度器顺序处理任务，一个任务内的批次顺序上送；
 * 3. 每批调用一次国家平台接口，请求报文、响应报文、HTTP状态码、平台流水号全部落库，可追踪；
 * 4. 某批失败（有条目失败或网络异常）时，根据任务 continueOnFail 配置决定继续后续批次还是暂停任务；
 * 5. 应用重启后自动恢复：把残留的“上送中”数据/批次回滚为待上送，排队中的任务继续执行。
 */
@Service
public class ReportQueueService {

    private static final Logger logger = LoggerFactory.getLogger(ReportQueueService.class);

    /** 待上报 */
    public static final int STATUS_PENDING = 0;
    /** 上送成功 */
    public static final int STATUS_SUCCESS = 1;
    /** 上送失败 */
    public static final int STATUS_FAILED = 2;
    /** 上送中 */
    public static final int STATUS_SENDING = 3;

    private static final String T_PENDING = "PENDING";
    private static final String T_RUNNING = "RUNNING";
    private static final String T_PAUSED = "PAUSED";
    private static final String T_SUCCESS = "SUCCESS";
    private static final String T_PARTIAL = "PARTIAL";
    private static final String T_FAILED = "FAILED";

    private static final String B_PENDING = "PENDING";
    private static final String B_RUNNING = "RUNNING";
    private static final String B_SUCCESS = "SUCCESS";
    private static final String B_PARTIAL = "PARTIAL";
    private static final String B_FAILED = "FAILED";

    private final ReportTaskMapper taskMapper;
    private final ReportBatchMapper batchMapper;
    private final ExcelDataMapper excelDataMapper;
    private final ImportRecordMapper importRecordMapper;
    private final UserMapper userMapper;
    private final ReportQueueProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public ReportQueueService(ReportTaskMapper taskMapper,
                              ReportBatchMapper batchMapper,
                              ExcelDataMapper excelDataMapper,
                              ImportRecordMapper importRecordMapper,
                              UserMapper userMapper,
                              ReportQueueProperties properties,
                              ObjectMapper objectMapper,
                              @Qualifier("nationalPlatformRestTemplate") RestTemplate restTemplate) {
        this.taskMapper = taskMapper;
        this.batchMapper = batchMapper;
        this.excelDataMapper = excelDataMapper;
        this.importRecordMapper = importRecordMapper;
        this.userMapper = userMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /** 待调度任务ID队列 */
    private final LinkedBlockingQueue<Long> taskQueue = new LinkedBlockingQueue<>();
    /** 正在执行的任务集合，保证同一任务绝不并发执行 */
    private final java.util.Set<Long> runningTaskIds = ConcurrentHashMap.newKeySet();
    /** 调度线程池：单线程，保证任务/批次顺序执行 */
    private ThreadPoolExecutor executor;

    @PostConstruct
    public void init() {
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "report-queue-worker");
                    t.setDaemon(true);
                    return t;
                });
        recoverOnStartup();
        executor.submit(this::dispatchLoop);
        logger.info("上送队列调度器已启动");
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 启动恢复：回滚残留“上送中”状态，重新排队未完成任务
     */
    private void recoverOnStartup() {
        // 数据条目：上送中 -> 待上报
        excelDataMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ExcelData>()
                .eq(ExcelData::getReportStatus, STATUS_SENDING)
                .set(ExcelData::getReportStatus, STATUS_PENDING)
                .set(ExcelData::getReportMessage, "应用重启，状态回滚为待上送"));
        // 批次：上送中 -> 待上送（其条目已回滚，恢复后可重新发送）
        List<ReportBatch> runningBatches = batchMapper.selectList(
                new LambdaQueryWrapper<ReportBatch>().eq(ReportBatch::getStatus, B_RUNNING));
        for (ReportBatch b : runningBatches) {
            b.setStatus(B_PENDING);
            b.setErrorMessage("应用重启，本批已回滚为待上送");
            b.setStartTime(null);
            batchMapper.updateById(b);
        }
        // 任务：上送中 -> 暂停（等待人工确认继续）
        List<ReportTask> runningTasks = taskMapper.selectList(
                new LambdaQueryWrapper<ReportTask>().eq(ReportTask::getStatus, T_RUNNING));
        for (ReportTask t : runningTasks) {
            // 若当前批次失败，则按暂停处理
            boolean hasFailure = !batchMapper.selectList(new LambdaQueryWrapper<ReportBatch>()
                    .eq(ReportBatch::getTaskId, t.getId())
                    .in(ReportBatch::getStatus, B_FAILED, B_PARTIAL)).isEmpty();
            t.setStatus(hasFailure || !isContinue(t) ? T_PAUSED : T_PENDING);
            taskMapper.updateById(t);
            if (T_PENDING.equals(t.getStatus())) {
                enqueue(t.getId());
            }
        }
        // 排队中的任务重新入队
        List<ReportTask> pendingTasks = taskMapper.selectList(
                new LambdaQueryWrapper<ReportTask>().eq(ReportTask::getStatus, T_PENDING));
        for (ReportTask t : pendingTasks) {
            enqueue(t.getId());
        }
        logger.info("上送队列启动恢复完成：排队任务{}个，中断任务{}个",
                pendingTasks.size(), runningTasks.size());
    }

    /**
     * 原子地将任务放入队列（去重）
     */
    private void enqueue(Long taskId) {
        if (taskId != null) {
            // offer 前的并发去重：以队列对象为锁，避免 contains/offer 竞态导致重复入队
            synchronized (taskQueue) {
                if (!taskQueue.contains(taskId)) {
                    taskQueue.offer(taskId);
                }
            }
        }
    }

    /**
     * 调度主循环
     */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Long taskId = taskQueue.poll(200, TimeUnit.MILLISECONDS);
                if (taskId == null) {
                    continue;
                }
                // 同一任务已在执行则跳过，杜绝并发重复上送
                boolean added = runningTaskIds.add(taskId);
                if (!added) {
                    continue;
                }
                try {
                    ReportTask task = taskMapper.selectById(taskId);
                    if (task == null || isTerminal(task.getStatus()) || T_PAUSED.equals(task.getStatus())) {
                        continue;
                    }
                    runTask(task);
                } finally {
                    runningTaskIds.remove(taskId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("上送队列调度异常", e);
            }
        }
    }

    private boolean isTerminal(String status) {
        return T_SUCCESS.equals(status) || T_PARTIAL.equals(status)
                || T_FAILED.equals(status) || "CANCELLED".equals(status);
    }

    private boolean isContinue(ReportTask task) {
        return task.getContinueOnFail() != null && task.getContinueOnFail() == 1;
    }

    /**
     * 顺序执行一个任务的所有待上送批次
     */
    private void runTask(ReportTask task) {
        task.setStatus(T_RUNNING);
        if (task.getStartTime() == null) {
            task.setStartTime(LocalDateTime.now());
        }
        taskMapper.updateById(task);

        List<ReportBatch> batches = batchMapper.selectList(
                new LambdaQueryWrapper<ReportBatch>()
                        .eq(ReportBatch::getTaskId, task.getId())
                        .orderByAsc(ReportBatch::getBatchIndex));

        for (ReportBatch batch : batches) {
            // 每批执行前重新读取任务，响应暂停/恢复操作
            task = taskMapper.selectById(task.getId());
            if (task == null || T_PAUSED.equals(task.getStatus()) || isTerminal(task.getStatus())) {
                logger.info("任务{}被暂停/终止，停止调度剩余批次", task.getTaskNo());
                return;
            }
            // 只执行待上送批次；已失败批次保留留痕，失败数据通过“重试”生成新批次，避免重复上送
            if (!B_PENDING.equals(batch.getStatus())) {
                continue;
            }
            boolean batchFailed = processBatch(task, batch);
            refreshTaskCounters(task);

            if (batchFailed && !isContinue(task)) {
                // 配置为失败暂停：若后面还有待上送批次则挂起任务；
                // 若本批已是最后一批，则直接收尾（避免任务永远停在“已暂停”）
                boolean hasRemainingPending = batches.stream().skip(batches.indexOf(batch) + 1L)
                        .anyMatch(b -> B_PENDING.equals(b.getStatus()));
                if (hasRemainingPending) {
                    task = taskMapper.selectById(task.getId());
                    task.setStatus(T_PAUSED);
                    task.setErrorMessage(String.format("第%d批上送失败，已按配置暂停；可继续或重试该批",
                            batch.getBatchIndex()));
                    taskMapper.updateById(task);
                    logger.info("任务{}第{}批失败，任务暂停", task.getTaskNo(), batch.getBatchIndex());
                    return;
                }
            }

            if (properties.getIntervalMs() > 0) {
                sleepInterval(task.getId());
            }
        }

        finishTask(task.getId());
    }

    private void sleepInterval(Long taskId) {
        long remaining = properties.getIntervalMs();
        while (remaining > 0) {
            long step = Math.min(200, remaining);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= step;
            ReportTask latest = taskMapper.selectById(taskId);
            if (latest == null || T_PAUSED.equals(latest.getStatus())
                    || isTerminal(latest.getStatus())) {
                return;
            }
        }
    }

    /**
     * 执行单个批次上送。
     * @return true 表示本批存在失败（条目失败/平台拒收/网络异常）
     */
    private boolean processBatch(ReportTask task, ReportBatch batch) {
        batch.setStatus(B_RUNNING);
        batch.setStartTime(LocalDateTime.now());
        batch.setErrorMessage(null);
        batchMapper.updateById(batch);

        List<Long> dataIds = parseDataIds(batch.getDataIds());
        List<ExcelData> dataList = dataIds.isEmpty() ? new ArrayList<>()
                : excelDataMapper.selectBatchIds(dataIds);

        // 条目置为上送中
        for (ExcelData d : dataList) {
            d.setReportStatus(STATUS_SENDING);
            d.setReportMessage("上送中...");
            d.setReportTime(LocalDateTime.now());
            excelDataMapper.updateById(d);
        }

        NationalReportRequest request = buildRequest(task, batch, dataList);
        String requestJson;
        try {
            requestJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
        } catch (Exception e) {
            requestJson = "序列化失败: " + e.getMessage();
        }
        batch.setRequestBody(requestJson);
        batchMapper.updateById(batch);

        try {
            HttpEntity<NationalReportRequest> entity = new HttpEntity<>(request);
            ResponseEntity<NationalReportResponse> response = restTemplate.postForEntity(
                    properties.getPlatformUrl(), entity, NationalReportResponse.class);

            NationalReportResponse body = response.getBody();
            String responseJson;
            try {
                responseJson = body == null ? ""
                        : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            } catch (Exception e) {
                responseJson = "响应序列化失败: " + e.getMessage();
            }
            batch.setHttpStatus(response.getStatusCode().value());
            batch.setResponseBody(responseJson);
            if (body != null) {
                batch.setTraceId(body.getTraceId());
            }

            Map<String, NationalReportResponse.ItemResult> resultMap = new LinkedHashMap<>();
            if (body != null && body.getResults() != null) {
                for (NationalReportResponse.ItemResult r : body.getResults()) {
                    resultMap.put(r.getId() == null ? null : String.valueOf(r.getId()), r);
                }
            }

            int success = 0;
            int fail = 0;
            for (ExcelData d : dataList) {
                NationalReportResponse.ItemResult r = resultMap.get(String.valueOf(d.getId()));
                if (r != null && Boolean.TRUE.equals(r.getSuccess())) {
                    markItem(d, STATUS_SUCCESS, "上送成功，平台流水号：" + body.getTraceId());
                    success++;
                } else {
                    String msg = r != null && r.getErrorMsg() != null ? r.getErrorMsg()
                            : (body != null ? body.getMessage() : "平台未返回该条目结果");
                    markItem(d, STATUS_FAILED, msg);
                    fail++;
                }
            }

            batch.setSuccessCount(success);
            batch.setFailCount(fail);
            batch.setStatus(fail == 0 ? B_SUCCESS : (success == 0 ? B_FAILED : B_PARTIAL));
            if (fail > 0) {
                batch.setErrorMessage(String.format("平台HTTP %d，%d条失败，流水号：%s",
                        response.getStatusCode().value(), fail, body == null ? "-" : body.getTraceId()));
            }
            batch.setEndTime(LocalDateTime.now());
            batchMapper.updateById(batch);
            return fail > 0;

        } catch (RestClientException e) {
            // HTTP 4xx/5xx 有错误体时 RestTemplate 会抛 HttpStatusCodeException
            handleTransportError(batch, dataList, e);
            return true;
        } catch (Exception e) {
            logger.error("批次上送异常 task={}, batchIndex={}", task.getTaskNo(), batch.getBatchIndex(), e);
            handleTransportError(batch, dataList, e);
            return true;
        }
    }

    private void handleTransportError(ReportBatch batch, List<ExcelData> dataList, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String responseBody = null;
        Integer httpStatus = null;
        if (e instanceof org.springframework.web.client.HttpStatusCodeException hce) {
            httpStatus = hce.getStatusCode().value();
            responseBody = hce.getResponseBodyAsString();
        }
        for (ExcelData d : dataList) {
            markItem(d, STATUS_FAILED, "上送异常：" + truncate(message, 400));
        }
        batch.setHttpStatus(httpStatus);
        batch.setResponseBody(responseBody);
        batch.setSuccessCount(0);
        batch.setFailCount(dataList.size());
        batch.setStatus(B_FAILED);
        batch.setErrorMessage(truncate("调用国家平台失败：" + message, 900));
        batch.setEndTime(LocalDateTime.now());
        batchMapper.updateById(batch);
    }

    private void markItem(ExcelData d, int status, String message) {
        d.setReportStatus(status);
        d.setReportMessage(truncate(message, 500));
        d.setReportTime(LocalDateTime.now());
        excelDataMapper.updateById(d);
    }

    private NationalReportRequest buildRequest(ReportTask task, ReportBatch batch, List<ExcelData> dataList) {
        NationalReportRequest request = new NationalReportRequest();
        request.setTaskNo(task.getTaskNo());
        request.setBatchNo(task.getBatchNo());
        request.setBatchIndex(batch.getBatchIndex());
        request.setTotalCount(dataList.size());
        request.setTimestamp(System.currentTimeMillis());

        List<NationalReportRequest.Item> items = new ArrayList<>();
        for (ExcelData d : dataList) {
            NationalReportRequest.Item item = new NationalReportRequest.Item();
            item.setId(d.getId());
            item.setDataCode(d.getDataCode());
            item.setName(d.getName());
            item.setIdCard(d.getIdCard());
            item.setPhone(d.getPhone());
            item.setAmount(d.getAmount() == null ? null : d.getAmount().toPlainString());
            item.setAddress(d.getAddress());
            items.add(item);
        }
        request.setItems(items);
        return request;
    }

    /**
     * 根据条目实际状态刷新任务成功/失败计数。
     * 注意：totalCount 是创建任务时入队的数据量，不被覆盖（重试失败数据时为同一批数据，计数仍守恒）。
     */
    private void refreshTaskCounters(ReportTask task) {
        ReportStatusCountDTO count = getStatusCount(task.getBatchNo());
        task.setSuccessCount((int) count.getSuccess().longValue());
        task.setFailCount((int) count.getFailed().longValue());
        taskMapper.updateById(task);
    }

    private void finishTask(Long taskId) {
        ReportTask task = taskMapper.selectById(taskId);
        if (task == null || isTerminal(task.getStatus()) || T_PAUSED.equals(task.getStatus())) {
            return;
        }
        refreshTaskCounters(task);
        task = taskMapper.selectById(taskId);
        long pending = countByStatus(task.getBatchNo(), STATUS_PENDING)
                + countByStatus(task.getBatchNo(), STATUS_SENDING);
        if (pending > 0) {
            // 仍有未处理数据（理论上不该发生），保持暂停
            task.setStatus(T_PAUSED);
            task.setErrorMessage("仍存在未上送数据");
        } else if (task.getFailCount() == 0) {
            task.setStatus(T_SUCCESS);
            task.setErrorMessage(null);
        } else if (task.getSuccessCount() == 0) {
            task.setStatus(T_FAILED);
        } else {
            task.setStatus(T_PARTIAL);
        }
        task.setEndTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    // ====================== 对外操作 ======================

    /**
     * 创建上送任务并入队
     */
    public ReportTask createTask(CreateReportTaskRequest request, Long operatorId) {
        String batchNo = request.getBatchNo();
        if (batchNo == null || batchNo.isBlank()) {
            throw new IllegalArgumentException("批次号不能为空");
        }
        int batchSize = request.getBatchSize() != null && request.getBatchSize() > 0
                ? request.getBatchSize() : properties.getBatchSize();
        boolean continueOnFail = request.getContinueOnFail() != null
                ? request.getContinueOnFail() : properties.isContinueOnFail();
        boolean retryFailed = "FAILED_ONLY".equalsIgnoreCase(request.getScope());

        // 存在进行中的任务时不允许重复创建
        Long active = taskMapper.selectCount(new LambdaQueryWrapper<ReportTask>()
                .eq(ReportTask::getBatchNo, batchNo)
                .in(ReportTask::getStatus, T_PENDING, T_RUNNING, T_PAUSED));
        if (active != null && active > 0) {            throw new IllegalStateException("该批次已有进行中或已暂停的上送任务，请继续或重试原任务");
        }

        List<ExcelData> targets = excelDataMapper.selectList(new LambdaQueryWrapper<ExcelData>()
                .eq(ExcelData::getBatchNo, batchNo)
                .eq(ExcelData::getReportStatus, retryFailed ? STATUS_FAILED : STATUS_PENDING)
                .orderByAsc(ExcelData::getId));
        if (targets.isEmpty()) {
            throw new IllegalStateException(retryFailed ? "没有可重试的失败数据" : "没有待上送的数据");
        }

        ImportRecord importRecord = importRecordMapper.selectOne(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getBatchNo, batchNo));
        User operator = operatorId == null ? null : userMapper.selectById(operatorId);

        ReportTask task = new ReportTask();
        task.setTaskNo("RT" + IdUtil.fastSimpleUUID().toUpperCase().substring(0, 16));
        task.setBatchNo(batchNo);
        task.setFileName(importRecord == null ? null : importRecord.getFileName());
        task.setTotalCount(targets.size());
        task.setBatchSize(batchSize);
        task.setTotalBatches((targets.size() + batchSize - 1) / batchSize);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setContinueOnFail(continueOnFail ? 1 : 0);
        task.setStatus(T_PENDING);
        task.setOperatorId(operatorId);
        task.setOperatorName(operator == null ? "系统" : operator.getRealName());
        taskMapper.insert(task);

        int maxIndex = 0;

        List<List<ExcelData>> chunks = partition(targets, batchSize);
        for (int i = 0; i < chunks.size(); i++) {
            List<ExcelData> chunk = chunks.get(i);
            ReportBatch batch = new ReportBatch();
            batch.setTaskId(task.getId());
            batch.setTaskNo(task.getTaskNo());
            batch.setBatchNo(batchNo);
            batch.setBatchIndex(maxIndex + i + 1);
            batch.setDataIds(toJson(chunk.stream().map(ExcelData::getId).collect(Collectors.toList())));
            batch.setTotalCount(chunk.size());
            batch.setSuccessCount(0);
            batch.setFailCount(0);
            batch.setStatus(B_PENDING);
            batch.setRetryCount(retryFailed ? 1 : 0);
            batchMapper.insert(batch);
        }

        // 重试任务：先把失败条目重置为待上送
        if (retryFailed) {
            resetItemsToPending(targets);
        }

        logger.info("创建上送任务: taskNo={}, batchNo={}, 数据{}条, 分{}批, 失败策略={}",
                task.getTaskNo(), batchNo, targets.size(), task.getTotalBatches(),
                continueOnFail ? "继续" : "暂停");
        enqueue(task.getId());
        return task;
    }

    /**
     * 暂停任务
     */
    public void pauseTask(Long taskId) {
        ReportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (T_PENDING.equals(task.getStatus()) || T_RUNNING.equals(task.getStatus())) {
            task.setStatus(T_PAUSED);
            task.setErrorMessage("用户手动暂停");
            taskMapper.updateById(task);
        }
    }

    /**
     * 恢复任务：继续执行剩余待上送批次
     */
    public void resumeTask(Long taskId) {
        ReportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!T_PAUSED.equals(task.getStatus())) {
            throw new IllegalStateException("仅暂停状态的任务可以继续");
        }
        task.setStatus(T_PENDING);
        task.setErrorMessage(null);
        taskMapper.updateById(task);
        enqueue(taskId);
    }

    /**
     * 重试任务中的失败数据（生成新批次继续上送）
     */
    public ReportTask retryTaskFailed(Long taskId) {
        ReportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }

        long failed = countByStatus(task.getBatchNo(), STATUS_FAILED);
        if (failed == 0) {
            throw new IllegalStateException("没有失败数据需要重试");
        }

        // 复用同一任务：在其下追加重试批次
        List<ExcelData> targets = excelDataMapper.selectList(new LambdaQueryWrapper<ExcelData>()
                .eq(ExcelData::getBatchNo, task.getBatchNo())
                .eq(ExcelData::getReportStatus, STATUS_FAILED)
                .orderByAsc(ExcelData::getId));
        appendRetryBatches(task, targets);
        resetItemsToPending(targets);
        task.setStatus(T_PENDING);
        task.setErrorMessage(null);
        taskMapper.updateById(task);
        enqueue(task.getId());
        return task;
    }

    /**
     * 重试单个批次
     */
    public void retryBatch(Long batchId) {
        ReportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("批次不存在");
        }
        if (!B_FAILED.equals(batch.getStatus()) && !B_PARTIAL.equals(batch.getStatus())) {
            throw new IllegalStateException("仅失败/部分成功的批次可以重试");
        }
        ReportTask task = taskMapper.selectById(batch.getTaskId());
        if (task == null) {
            throw new IllegalStateException("所属任务不存在");
        }

        List<Long> ids = parseDataIds(batch.getDataIds());
        List<ExcelData> failedItems = ids.isEmpty() ? new ArrayList<>()
                : excelDataMapper.selectList(new LambdaQueryWrapper<ExcelData>()
                        .in(ExcelData::getId, ids)
                        .eq(ExcelData::getReportStatus, STATUS_FAILED)
                        .orderByAsc(ExcelData::getId));
        if (failedItems.isEmpty()) {
            throw new IllegalStateException("该批次下没有失败数据");
        }
        appendRetryBatches(task, failedItems);
        resetItemsToPending(failedItems);

        // 任务若处于暂停/终态，转为排队继续
        if (T_PAUSED.equals(task.getStatus()) || isTerminal(task.getStatus())) {
            task.setStatus(T_PENDING);
            task.setErrorMessage(null);
            task.setEndTime(null);
            taskMapper.updateById(task);
            enqueue(task.getId());
        }
    }

    /**
     * 在任务下追加重试批次
     */
    private void appendRetryBatches(ReportTask task, List<ExcelData> targets) {
        int batchSize = task.getBatchSize() == null ? properties.getBatchSize() : task.getBatchSize();
        Integer maxIndex = batchMapper.selectList(new LambdaQueryWrapper<ReportBatch>()
                        .eq(ReportBatch::getTaskId, task.getId())
                        .orderByDesc(ReportBatch::getBatchIndex).last("LIMIT 1"))
                .stream().findFirst().map(ReportBatch::getBatchIndex).orElse(0);
        List<List<ExcelData>> chunks = partition(targets, batchSize);
        for (int i = 0; i < chunks.size(); i++) {
            List<ExcelData> chunk = chunks.get(i);
            ReportBatch b = new ReportBatch();
            b.setTaskId(task.getId());
            b.setTaskNo(task.getTaskNo());
            b.setBatchNo(task.getBatchNo());
            b.setBatchIndex(maxIndex + i + 1);
            b.setDataIds(toJson(chunk.stream().map(ExcelData::getId).collect(Collectors.toList())));
            b.setTotalCount(chunk.size());
            b.setSuccessCount(0);
            b.setFailCount(0);
            b.setStatus(B_PENDING);
            b.setRetryCount(1);
            batchMapper.insert(b);
        }
        task.setTotalBatches(maxIndex + chunks.size());
        taskMapper.updateById(task);
    }

    private void resetItemsToPending(List<ExcelData> items) {
        for (ExcelData d : items) {
            d.setReportStatus(STATUS_PENDING);
            d.setReportMessage("等待重新上送");
            d.setReportTime(null);
            excelDataMapper.updateById(d);
        }
    }

    // ====================== 查询 ======================

    public Page<ReportTask> pageTasks(Integer pageNum, Integer pageSize) {
        Page<ReportTask> page = new Page<>(pageNum, pageSize);
        return taskMapper.selectPage(page,
                new LambdaQueryWrapper<ReportTask>().orderByDesc(ReportTask::getCreateTime));
    }

    public ReportTask getTask(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    public ReportTask getTaskByBatchNo(String batchNo) {
        return taskMapper.selectOne(new LambdaQueryWrapper<ReportTask>()
                .eq(ReportTask::getBatchNo, batchNo)
                .orderByDesc(ReportTask::getCreateTime)
                .last("LIMIT 1"));
    }

    public List<ReportBatch> listBatches(Long taskId) {
        return batchMapper.selectList(new LambdaQueryWrapper<ReportBatch>()
                .eq(ReportBatch::getTaskId, taskId)
                .orderByAsc(ReportBatch::getBatchIndex));
    }

    public ReportBatch getBatch(Long batchId) {
        return batchMapper.selectById(batchId);
    }

    /**
     * 按导入批次统计 待上送/上送中/成功/失败 数量
     */
    public ReportStatusCountDTO getStatusCount(String batchNo) {
        long pending = countByStatus(batchNo, STATUS_PENDING);
        long sending = countByStatus(batchNo, STATUS_SENDING);
        long success = countByStatus(batchNo, STATUS_SUCCESS);
        long failed = countByStatus(batchNo, STATUS_FAILED);
        return ReportStatusCountDTO.builder()
                .batchNo(batchNo)
                .pending(pending)
                .sending(sending)
                .success(success)
                .failed(failed)
                .total(pending + sending + success + failed)
                .build();
    }

    private long countByStatus(String batchNo, int status) {
        Long c = excelDataMapper.selectCount(new LambdaQueryWrapper<ExcelData>()
                .eq(ExcelData::getBatchNo, batchNo)
                .eq(ExcelData::getReportStatus, status));
        return c == null ? 0 : c;
    }

    // ====================== 工具方法 ======================

    private List<Long> parseDataIds(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            logger.error("解析批次数据ID失败: {}", json, e);
            return new ArrayList<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON序列化失败", e);
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
