package com.excel.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.dto.report.CreateReportJobRequest;
import com.excel.dto.report.NationalReportRequest;
import com.excel.dto.report.NationalReportResponse;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportRecord;
import com.excel.entity.ReportBatch;
import com.excel.entity.ReportJob;
import com.excel.mapper.ExcelDataMapper;
import com.excel.mapper.ImportRecordMapper;
import com.excel.mapper.ReportBatchMapper;
import com.excel.mapper.ReportJobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 上送队列服务
 * <p>
 * 导入完成后将数据按批加入上送队列，由独立线程异步把清单逐批发送到国家平台模拟接口。
 * 每批的请求报文、响应报文、耗时、错误均落库，可全程追踪；
 * 某批失败后根据 failStrategy 决定继续后续批次还是暂停。
 */
@Service
@RequiredArgsConstructor
public class ReportQueueService {

    private static final Logger logger = LoggerFactory.getLogger(ReportQueueService.class);

    public static final String STRATEGY_CONTINUE = "CONTINUE";
    public static final String STRATEGY_PAUSE = "PAUSE";

    private final ReportJobMapper reportJobMapper;
    private final ReportBatchMapper reportBatchMapper;
    private final ExcelDataMapper excelDataMapper;
    private final ImportRecordMapper importRecordMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 每个任务一把锁，保证同一任务串行处理（暂停/继续/重试时状态一致）
     */
    private final Map<Long, ReentrantLock> jobLocks = new ConcurrentHashMap<>();

    @Value("${report.national-platform-url:http://localhost:8080/api/mock/national/report}")
    private String nationalPlatformUrl;

    // ============================== 创建任务 ==============================

    /**
     * 将导入批次的待上送数据加入上送队列，拆分为多个批次后异步执行
     */
    public ReportJob createJob(String batchNo, CreateReportJobRequest request,
                               Long operatorId, String operatorName) {
        // 防止同一导入批次存在进行中的上送任务，避免重复上送
        Long activeCount = reportJobMapper.selectCount(
                new LambdaQueryWrapper<ReportJob>()
                        .eq(ReportJob::getBatchNo, batchNo)
                        .in(ReportJob::getStatus, List.of("PENDING", "SENDING", "PAUSED")));
        if (activeCount > 0) {
            throw new IllegalStateException("该导入批次已存在进行中的上送任务，请勿重复提交");
        }

        List<ExcelData> pendingList = excelDataMapper.selectByBatchAndStatus(batchNo, 0);
        if (pendingList.isEmpty()) {
            throw new IllegalStateException("没有待上送的数据");
        }

        int batchSize = request.getBatchSize() == null ? 500 : request.getBatchSize();
        String strategy = STRATEGY_CONTINUE.equalsIgnoreCase(request.getFailStrategy())
                ? STRATEGY_CONTINUE : STRATEGY_PAUSE;

        ImportRecord importRecord = importRecordMapper.selectOne(
                new LambdaQueryWrapper<ImportRecord>().eq(ImportRecord::getBatchNo, batchNo).last("LIMIT 1"));

        ReportJob job = new ReportJob();
        job.setJobNo("JOB" + IdUtil.fastSimpleUUID().substring(0, 16).toUpperCase());
        job.setBatchNo(batchNo);
        job.setFileName(importRecord != null ? importRecord.getFileName() : null);
        job.setTotalCount(pendingList.size());
        job.setPendingCount(pendingList.size());
        job.setSendingCount(0);
        job.setSuccessCount(0);
        job.setFailCount(0);
        job.setBatchSize(batchSize);
        job.setTotalBatches((pendingList.size() + batchSize - 1) / batchSize);
        job.setFailStrategy(strategy);
        job.setStatus("PENDING");
        job.setMessage("任务已创建，等待上送");
        job.setOperatorId(operatorId);
        job.setOperatorName(operatorName);
        reportJobMapper.insert(job);

        // 拆分批次并落库（固化每批数据ID，发送时按ID取数，避免前批处理后定位错位）
        int seq = 1;
        for (int i = 0; i < pendingList.size(); i += batchSize) {
            List<ExcelData> slice = pendingList.subList(i, Math.min(i + batchSize, pendingList.size()));
            String dataIds = slice.stream().map(d -> String.valueOf(d.getId()))
                    .collect(java.util.stream.Collectors.joining(","));
            ReportBatch rb = new ReportBatch();
            rb.setJobId(job.getId());
            rb.setJobNo(job.getJobNo());
            rb.setBatchNo(batchNo);
            rb.setSeqNo(seq++);
            rb.setDataIds(dataIds);
            rb.setTotalCount(slice.size());
            rb.setSuccessCount(0);
            rb.setFailCount(0);
            rb.setStatus("PENDING");
            rb.setRetryCount(0);
            reportBatchMapper.insert(rb);
        }

        logger.info("上送任务已创建: jobNo={}, batchNo={}, 总数={}, 批次数={}, 失败策略={}",
                job.getJobNo(), batchNo, pendingList.size(), job.getTotalBatches(), strategy);

        // 异步执行，不阻塞当前请求/页面
        runJobAsync(job.getId());
        return job;
    }

    // ============================== 异步执行 ==============================

    @Async("reportExecutor")
    public void runJobAsync(Long jobId) {
        runJob(jobId);
    }

    /**
     * 串行处理一个任务中所有待处理批次
     */
    private void runJob(Long jobId) {
        ReentrantLock lock = jobLocks.computeIfAbsent(jobId, k -> new ReentrantLock());
        lock.lock();
        try {
            ReportJob job = reportJobMapper.selectById(jobId);
            if (job == null || "CANCELLED".equals(job.getStatus())) {
                return;
            }

            if (job.getStartTime() == null) {
                job.setStartTime(LocalDateTime.now());
            }
            job.setStatus("SENDING");
            job.setMessage("正在上送...");
            reportJobMapper.updateById(job);

            List<ReportBatch> batches = reportBatchMapper.selectList(
                    new LambdaQueryWrapper<ReportBatch>()
                            .eq(ReportBatch::getJobId, jobId)
                            .orderByAsc(ReportBatch::getSeqNo));

            for (ReportBatch batch : batches) {
                // 每轮循环前刷新任务状态，响应暂停/继续/取消
                job = reportJobMapper.selectById(jobId);
                if (job == null || "CANCELLED".equals(job.getStatus())) {
                    return;
                }
                // 用户手动暂停：当前批次已执行完，后续批次保持待上送
                if ("PAUSED".equals(job.getStatus())) {
                    return;
                }

                // 只处理待上送批次；已失败批次仅在用户点"重试"后重置为PENDING才会重发
                if (!"PENDING".equals(batch.getStatus())) {
                    continue;
                }

                boolean ok = sendOneBatch(job, batch);

                if (!ok) {
                    if (STRATEGY_PAUSE.equals(job.getFailStrategy())) {
                        // 暂停：后续批次保持 PENDING
                        ReportJob pause = reportJobMapper.selectById(jobId);
                        pause.setStatus("PAUSED");
                        pause.setMessage("第" + batch.getSeqNo() + "批上送失败，已按配置暂停，请处理后继续");
                        pause.setEndTime(LocalDateTime.now());
                        reportJobMapper.updateById(pause);
                        logger.warn("任务{}第{}批失败，按策略暂停", job.getJobNo(), batch.getSeqNo());
                        return;
                    } else {
                        // 继续：失败批次保留 FAILED，继续后续批次
                        logger.warn("任务{}第{}批失败，按策略继续后续批次", job.getJobNo(), batch.getSeqNo());
                    }
                }
            }

            finishJob(jobId);
        } catch (Exception e) {
            logger.error("上送任务执行异常 jobId={}", jobId, e);
            ReportJob job = reportJobMapper.selectById(jobId);
            if (job != null && !"CANCELLED".equals(job.getStatus())) {
                job.setStatus("FAILED");
                job.setMessage("任务执行异常: " + e.getMessage());
                job.setEndTime(LocalDateTime.now());
                reportJobMapper.updateById(job);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 发送单个批次，并根据平台返回逐条更新数据状态、批次追踪信息、任务统计
     *
     * @return 本批是否完全成功
     */
    private boolean sendOneBatch(ReportJob job, ReportBatch batch) {
        // 按创建批次时固化的数据ID清单取数（首次/重试均取这些数据）
        List<Long> dataIdList = parseDataIds(batch.getDataIds());
        if (dataIdList.isEmpty()) {
            // 没有数据，直接标记成功
            batch.setStatus("SUCCESS");
            batch.setTotalCount(0);
            reportBatchMapper.updateById(batch);
            return true;
        }
        List<ExcelData> slice = excelDataMapper.selectBatchIds(dataIdList);
        // 仅上送尚未成功的数据：
        //  - 继续任务时循环只发PENDING批次，不会重发已失败批次
        //  - 重试时失败批次被重置为PENDING，其失败数据也被重置为0后再次上送
        slice.removeIf(d -> d.getReportStatus() != null && d.getReportStatus() == 1);
        if (slice.isEmpty()) {
            // 本批数据均已成功（无待上送内容），标记批次成功
            batch.setStatus("SUCCESS");
            reportBatchMapper.updateById(batch);
            return true;
        }
        slice.sort(java.util.Comparator.comparing(ExcelData::getId));

        // 组装请求清单
        NationalReportRequest request = new NationalReportRequest();
        request.setJobNo(job.getJobNo());
        request.setBatchNo(job.getBatchNo());
        request.setSeqNo(batch.getSeqNo());
        List<NationalReportRequest.NationalReportItem> items = new ArrayList<>();
        for (ExcelData d : slice) {
            NationalReportRequest.NationalReportItem item = new NationalReportRequest.NationalReportItem();
            item.setId(d.getId());
            item.setDataCode(d.getDataCode());
            item.setName(d.getName());
            item.setIdCard(d.getIdCard());
            item.setPhone(d.getPhone());
            item.setAmount(d.getAmount() == null ? null : d.getAmount().toPlainString());
            item.setAddress(d.getAddress());
            item.setRemark(d.getRemark());
            items.add(item);
        }
        request.setItems(items);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            requestBody = "请求报文序列化失败: " + e.getMessage();
        }

        // 标记上送中：批次与本批数据均置为上送中（页面可展示上送中数量）
        batch.setStatus("SENDING");
        batch.setRequestUrl(nationalPlatformUrl);
        batch.setRequestBody(requestBody);
        batch.setSendTime(LocalDateTime.now());
        reportBatchMapper.updateById(batch);
        List<Long> sliceIds = slice.stream().map(ExcelData::getId).collect(java.util.stream.Collectors.toList());
        excelDataMapper.markSendingByIds(sliceIds);
        recomputeJobCounts(job.getId());

        long start = System.currentTimeMillis();
        String responseBody = null;
        Integer httpStatus = null;
        String errorMessage = null;
        NationalReportResponse parsed = null;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            var resp = restTemplate.postForEntity(nationalPlatformUrl, entity, NationalReportResponse.class);
            httpStatus = resp.getStatusCode().value();
            parsed = resp.getBody();
            responseBody = parsed == null ? null : objectMapper.writeValueAsString(parsed);
        } catch (HttpStatusCodeException e) {
            httpStatus = e.getStatusCode().value();
            responseBody = e.getResponseBodyAsString();
            errorMessage = "国家平台HTTP错误: " + e.getStatusCode();
        } catch (Exception e) {
            errorMessage = "请求国家平台异常: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }

        long duration = System.currentTimeMillis() - start;

        // 构造成功/失败映射（以平台返回明细为准，结合整批标志兜底）
        Map<Long, String> failMap = new HashMap<>();
        boolean platformSuccess = parsed != null && Boolean.TRUE.equals(parsed.getSuccess());

        if (parsed != null && parsed.getResults() != null && !parsed.getResults().isEmpty()) {
            for (NationalReportResponse.ItemResult r : parsed.getResults()) {
                if (!Boolean.TRUE.equals(r.getSuccess())) {
                    failMap.put(r.getId(), r.getMessage() == null ? parsed.getMessage() : r.getMessage());
                }
            }
        } else if (!platformSuccess) {
            // 无明细应答（整批拒收/5xx/网络异常）：本次发送的全部数据记为失败
            String allFailMsg;
            if (parsed != null && parsed.getMessage() != null) {
                allFailMsg = "国家平台返回：" + parsed.getMessage();
            } else {
                allFailMsg = errorMessage != null ? errorMessage : "国家平台无有效响应";
            }
            for (ExcelData d : slice) {
                failMap.put(d.getId(), allFailMsg);
            }
        }

        // 若等待响应期间任务已被取消，则按响应结果更新数据后终止，不再继续后续批次
        // 逐条更新数据上送状态
        int successNum = 0;
        int failNum = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ExcelData d : slice) {
            String err = failMap.get(d.getId());
            if (err == null) {
                updateDataReportResult(d.getId(), 1, "上送成功", now);
                successNum++;
            } else {
                updateDataReportResult(d.getId(), 2, truncate(err, 480), now);
                failNum++;
            }
        }
        // 本批发送的所有数据均成功，才算批次成功（含重试只发失败数据的场景）
        boolean batchFullySuccess = failNum == 0;

        // 已取消的批次保持 CANCELLED，但仍留存本次请求/响应报文
        ReportBatch latest = reportBatchMapper.selectById(batch.getId());
        if (latest != null && "CANCELLED".equals(latest.getStatus())) {
            latest.setSuccessCount(successNum);
            latest.setFailCount(failNum);
            latest.setResponseBody(truncate(responseBody, 60000));
            latest.setHttpStatus(httpStatus);
            latest.setErrorMessage(truncate(errorMessage, 900));
            latest.setDurationMs(duration);
            reportBatchMapper.updateById(latest);
            recomputeJobCounts(job.getId());
            return false;
        }
        batch.setStatus(batchFullySuccess ? "SUCCESS" : "FAILED");
        batch.setSuccessCount(successNum);
        batch.setFailCount(failNum);
        batch.setResponseBody(truncate(responseBody, 60000));
        batch.setHttpStatus(httpStatus);
        batch.setErrorMessage(truncate(errorMessage, 900));
        batch.setDurationMs(duration);
        reportBatchMapper.updateById(batch);

        // 刷新任务统计
        recomputeJobCounts(job.getId());

        logger.info("任务{}第{}批完成: 本次发送{}条 成功{} 失败{} 耗时{}ms {}",
                job.getJobNo(), batch.getSeqNo(), slice.size(), successNum, failNum, duration,
                batchFullySuccess ? "" : ("[" + (errorMessage != null ? errorMessage : "部分失败") + "]"));

        return batchFullySuccess;
    }

    private void updateDataReportResult(Long id, int status, String message, LocalDateTime time) {
        ExcelData update = new ExcelData();
        update.setId(id);
        update.setReportStatus(status);
        update.setReportMessage(message);
        update.setReportTime(time);
        excelDataMapper.updateById(update);
    }

    /**
     * 根据 excel_data 实际状态重新统计任务的待上送/上送中/成功/失败数量
     */
    private void recomputeJobCounts(Long jobId) {
        ReportJob job = reportJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        List<ExcelData> all = excelDataMapper.selectList(
                new LambdaQueryWrapper<ExcelData>().eq(ExcelData::getBatchNo, job.getBatchNo()));
        int pending = 0, sending = 0, success = 0, fail = 0;
        for (ExcelData d : all) {
            Integer s = d.getReportStatus();
            if (s == null || s == 0) {
                pending++;
            } else if (s == 1) {
                success++;
            } else if (s == 2) {
                fail++;
            } else if (s == 3) {
                sending++;
            }
        }
        job.setTotalCount(all.size());
        job.setPendingCount(pending);
        job.setSendingCount(sending);
        job.setSuccessCount(success);
        job.setFailCount(fail);
        reportJobMapper.updateById(job);
    }

    /**
     * 全部批次处理完，汇总任务最终状态
     */
    private void finishJob(Long jobId) {
        recomputeJobCounts(jobId);
        ReportJob job = reportJobMapper.selectById(jobId);
        List<ReportBatch> batches = reportBatchMapper.selectList(
                new LambdaQueryWrapper<ReportBatch>().eq(ReportBatch::getJobId, jobId));
        long failedBatches = batches.stream().filter(b -> "FAILED".equals(b.getStatus())).count();
        long pendingBatches = batches.stream().filter(b -> "PENDING".equals(b.getStatus())).count();

        if (pendingBatches > 0) {
            job.setStatus("PAUSED");
            job.setMessage("仍有未上送批次");
        } else if (job.getFailCount() == 0 && failedBatches == 0) {
            job.setStatus("SUCCESS");
            job.setMessage("全部上送成功");
        } else if (job.getSuccessCount() == 0) {
            job.setStatus("FAILED");
            job.setMessage("全部上送失败");
        } else {
            job.setStatus("PARTIAL_SUCCESS");
            job.setMessage(String.format("上送完成：成功%d条，失败%d条", job.getSuccessCount(), job.getFailCount()));
        }
        job.setEndTime(LocalDateTime.now());
        reportJobMapper.updateById(job);
    }

    // ============================== 控制操作 ==============================

    /**
     * 暂停任务（运行中的当前批次执行完后停止，后续批次保持待上送）
     */
    public void pause(Long jobId) {
        ReportJob job = reportJobMapper.selectById(jobId);
        if (job == null) {
            throw new IllegalStateException("任务不存在");
        }
        if (!"SENDING".equals(job.getStatus()) && !"PENDING".equals(job.getStatus())) {
            throw new IllegalStateException("当前状态不允许暂停: " + job.getStatus());
        }
        job.setStatus("PAUSED");
        job.setMessage("用户手动暂停");
        reportJobMapper.updateById(job);
    }

    /**
     * 继续任务（用于暂停后恢复，未完成批次继续上送）
     */
    public void resume(Long jobId) {
        ReportJob job = reportJobMapper.selectById(jobId);
        if (job == null) {
            throw new IllegalStateException("任务不存在");
        }
        if (!"PAUSED".equals(job.getStatus())) {
            throw new IllegalStateException("仅暂停状态的任务可以继续: " + job.getStatus());
        }
        runJobAsync(jobId);
    }

    /**
     * 重试：将失败数据重置为待上送、失败批次重置后继续执行（用于暂停后/部分成功/失败后重试）
     */
    public synchronized void retry(Long jobId) {
        ReportJob job = reportJobMapper.selectById(jobId);
        if (job == null) {
            throw new IllegalStateException("任务不存在");
        }
        if ("SENDING".equals(job.getStatus())) {
            throw new IllegalStateException("任务正在上送中，请等待当前批次完成");
        }
        List<ReportBatch> failedBatches = reportBatchMapper.selectList(
                new LambdaQueryWrapper<ReportBatch>()
                        .eq(ReportBatch::getJobId, jobId)
                        .eq(ReportBatch::getStatus, "FAILED"));
        if (failedBatches.isEmpty()) {
            throw new IllegalStateException("没有失败的批次，无需重试");
        }

        // 重置失败数据
        excelDataMapper.clearFailedByBatch(job.getBatchNo());

        // 重置失败批次为待上送
        for (ReportBatch b : failedBatches) {
            b.setStatus("PENDING");
            b.setRetryCount(b.getRetryCount() == null ? 1 : b.getRetryCount() + 1);
            b.setErrorMessage(null);
            b.setResponseBody(null);
            b.setHttpStatus(null);
            b.setDurationMs(null);
            reportBatchMapper.updateById(b);
        }

        job.setStatus("SENDING");
        job.setMessage("重试上送中...");
        job.setEndTime(null);
        reportJobMapper.updateById(job);
        recomputeJobCounts(jobId);

        runJobAsync(jobId);
    }

    /**
     * 取消任务：未上送批次标记取消，任务终止（已上送数据不回滚）
     */
    public void cancel(Long jobId) {
        ReportJob job = reportJobMapper.selectById(jobId);
        if (job == null) {
            throw new IllegalStateException("任务不存在");
        }
        if ("SUCCESS".equals(job.getStatus()) || "CANCELLED".equals(job.getStatus())) {
            throw new IllegalStateException("当前状态不允许取消: " + job.getStatus());
        }
        job.setStatus("CANCELLED");
        job.setMessage("用户取消任务");
        job.setEndTime(LocalDateTime.now());
        reportJobMapper.updateById(job);

        List<ReportBatch> unfinished = reportBatchMapper.selectList(
                new LambdaQueryWrapper<ReportBatch>()
                        .eq(ReportBatch::getJobId, jobId)
                        .in(ReportBatch::getStatus, "PENDING", "SENDING"));
        for (ReportBatch b : unfinished) {
            b.setStatus("CANCELLED");
            reportBatchMapper.updateById(b);
        }
        recomputeJobCounts(jobId);
    }

    // ============================== 查询 ==============================

    public Page<ReportJob> pageJobs(Integer pageNum, Integer pageSize, String status) {
        Page<ReportJob> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ReportJob> wrapper = new LambdaQueryWrapper<ReportJob>()
                .orderByDesc(ReportJob::getCreateTime);
        if (status != null && !status.isBlank()) {
            wrapper.eq(ReportJob::getStatus, status);
        }
        return reportJobMapper.selectPage(page, wrapper);
    }

    public ReportJob getJob(Long jobId) {
        return reportJobMapper.selectById(jobId);
    }

    public List<ReportBatch> listBatches(Long jobId) {
        return reportBatchMapper.selectList(
                new LambdaQueryWrapper<ReportBatch>()
                        .eq(ReportBatch::getJobId, jobId)
                        .orderByAsc(ReportBatch::getSeqNo));
    }

    public ReportBatch getBatch(Long batchId) {
        return reportBatchMapper.selectById(batchId);
    }

    /**
     * 应用启动时恢复中断任务：上次执行中状态为 SENDING 的任务，按其策略恢复或置为暂停
     */
    @PostConstruct
    public void recoverOnStartup() {
        List<ReportJob> interrupted = reportJobMapper.selectList(
                new LambdaQueryWrapper<ReportJob>().eq(ReportJob::getStatus, "SENDING"));
        for (ReportJob job : interrupted) {
            // 正在发送的批次重置为待上送
            List<ReportBatch> sending = reportBatchMapper.selectList(
                    new LambdaQueryWrapper<ReportBatch>()
                            .eq(ReportBatch::getJobId, job.getId())
                            .eq(ReportBatch::getStatus, "SENDING"));
            for (ReportBatch b : sending) {
                b.setStatus("PENDING");
                reportBatchMapper.updateById(b);
            }
            if (STRATEGY_CONTINUE.equals(job.getFailStrategy())) {
                logger.info("启动恢复：继续执行任务 {}", job.getJobNo());
                runJobAsync(job.getId());
            } else {
                logger.info("启动恢复：任务{}置为暂停，等待人工继续", job.getJobNo());
                job.setStatus("PAUSED");
                job.setMessage("服务重启，任务中断，请点击继续");
                reportJobMapper.updateById(job);
            }
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private List<Long> parseDataIds(String dataIds) {
        List<Long> ids = new ArrayList<>();
        if (dataIds == null || dataIds.isBlank()) {
            return ids;
        }
        for (String s : dataIds.split(",")) {
            if (!s.isBlank()) {
                ids.add(Long.parseLong(s.trim()));
            }
        }
        return ids;
    }
}
