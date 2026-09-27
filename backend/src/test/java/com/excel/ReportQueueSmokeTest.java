package com.excel;

import com.excel.config.ReportQueueProperties;
import com.excel.dto.CreateReportTaskRequest;
import com.excel.dto.ReportStatusCountDTO;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportRecord;
import com.excel.entity.ReportBatch;
import com.excel.entity.ReportTask;
import com.excel.mapper.ExcelDataMapper;
import com.excel.mapper.ImportRecordMapper;
import com.excel.service.ReportQueueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上送队列全链路冒烟：H2内存库 + 真实HTTP调用本机国家平台模拟接口。
 */
@ActiveProfiles("smoke")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportQueueSmokeTest {

    @Autowired private ReportQueueService queueService;
    @Autowired private ExcelDataMapper excelDataMapper;
    @Autowired private ImportRecordMapper importRecordMapper;
    @Autowired private ReportQueueProperties properties;

    @LocalServerPort private int port;

    private double oldItem, oldBatch, oldError;

    @BeforeEach
    void setUp() {
        properties.setPlatformUrl("http://localhost:" + port + "/api/mock/national/report");
        oldItem = properties.getItemFailRate();
        oldBatch = properties.getBatchFailRate();
        oldError = properties.getBatchErrorRate();
        // 确定性：每条50%失败，不整批拒收，不系统异常 => 每批 PARTIAL
        properties.setItemFailRate(0.5);
        properties.setBatchFailRate(0.0);
        properties.setBatchErrorRate(0.0);
    }

    @AfterEach
    void tearDown() {
        properties.setItemFailRate(oldItem);
        properties.setBatchFailRate(oldBatch);
        properties.setBatchErrorRate(oldError);
    }

    private String seedData(int count) {
        String batchNo = "B" + System.nanoTime();
        ImportRecord rec = new ImportRecord();
        rec.setBatchNo(batchNo);
        rec.setFileName("smoke.xlsx");
        rec.setTotalCount(count);
        rec.setSuccessCount(count);
        rec.setFailCount(0);
        rec.setStatus(1);
        importRecordMapper.insert(rec);

        for (int i = 0; i < count; i++) {
            ExcelData d = new ExcelData();
            d.setDataCode(String.format("D%05d", i));
            d.setName("测试" + i);
            d.setIdCard("11010119900101" + String.format("%04d", i % 10000));
            d.setPhone("138%08d".formatted(i % 100000000));
            d.setBatchNo(batchNo);
            d.setReportStatus(0);
            excelDataMapper.insert(d);
        }
        return batchNo;
    }

    private CreateReportTaskRequest req(String batchNo, int batchSize, boolean continueOnFail) {
        CreateReportTaskRequest r = new CreateReportTaskRequest();
        r.setBatchNo(batchNo);
        r.setBatchSize(batchSize);
        r.setContinueOnFail(continueOnFail);
        return r;
    }

    private void waitFor(Long taskId, java.util.function.Predicate<ReportTask> pred, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            ReportTask t = queueService.getTask(taskId);
            if (t != null && pred.test(t)) return;
        }
        fail("等待任务状态超时");
    }

    private void waitFinished(Long taskId) {
        waitFor(taskId, t -> List.of("SUCCESS", "PARTIAL", "FAILED").contains(t.getStatus()), 20000);
    }

    @Test
    void continueMode_runsAllBatches_andTracesEveryRequestResponse() {
        String batchNo = seedData(60);
        ReportTask created = queueService.createTask(req(batchNo, 20, true), null);

        waitFinished(created.getId());

        ReportTask task = queueService.getTask(created.getId());
        assertEquals("PARTIAL", task.getStatus(), "每批均有失败条目且配置继续，应为部分成功");
        assertEquals(3, task.getTotalBatches());
        assertEquals(60, task.getTotalCount());
        assertEquals(task.getSuccessCount() + task.getFailCount(), 60);
        assertTrue(task.getSuccessCount() > 0 && task.getFailCount() > 0);

        // 每批请求/响应/HTTP码/流水号均留痕
        List<ReportBatch> batches = queueService.listBatches(task.getId());
        assertEquals(3, batches.size());
        for (ReportBatch b : batches) {
            assertEquals("PARTIAL", b.getStatus());
            assertEquals(20, b.getTotalCount());
            // 模拟平台对“部分条目校验失败”返回 HTTP 400
            assertEquals(400, b.getHttpStatus());
            assertNotNull(b.getTraceId());
            assertTrue(b.getTraceId().startsWith("NP"));
            assertTrue(b.getRequestBody().contains("\"taskNo\""));
            assertTrue(b.getRequestBody().contains(batchNo));
            assertTrue(b.getResponseBody().contains(b.getTraceId()));
            assertNotNull(b.getStartTime());
            assertNotNull(b.getEndTime());
        }

        // 状态数量统计守恒
        ReportStatusCountDTO c = queueService.getStatusCount(batchNo);
        assertEquals(60, c.getTotal());
        assertEquals(0, c.getPending());
        assertEquals(0, c.getSending());
        assertEquals(60, c.getSuccess() + c.getFailed());
    }

    @Test
    void pauseMode_stopsAfterFirstFailedBatch_thenResumeFinishes() {
        String batchNo = seedData(60);
        ReportTask created = queueService.createTask(req(batchNo, 20, false), null);
        Long taskId = created.getId();

        // 第一批失败后任务暂停，后两批保持待上送
        waitFor(taskId, t -> "PAUSED".equals(t.getStatus()), 20000);
        ReportTask paused = queueService.getTask(taskId);
        assertEquals("PAUSED", paused.getStatus(), "首批失败且配置暂停，任务应挂起");
        assertTrue(paused.getErrorMessage().contains("暂停"));

        List<ReportBatch> batches = queueService.listBatches(taskId);
        long pendingBatches = batches.stream().filter(b -> "PENDING".equals(b.getStatus())).count();
        assertEquals(2, pendingBatches, "后两批应保持待上送");
        assertEquals("PARTIAL", batches.get(0).getStatus());

        // “失败暂停”策略下，每遇到一个失败批次（且后面还有批次）都会暂停；循环继续直到全部批次发完
        for (int i = 0; i < 5; i++) {
            boolean hasPending = queueService.listBatches(taskId).stream()
                    .anyMatch(b -> "PENDING".equals(b.getStatus()));
            if (!hasPending) {
                break;
            }
            queueService.resumeTask(taskId);
            waitFor(taskId,
                    t -> List.of("PAUSED", "SUCCESS", "PARTIAL", "FAILED").contains(t.getStatus()),
                    20000);
        }
        waitFinished(taskId);

        ReportTask done = queueService.getTask(taskId);
        assertEquals("PARTIAL", done.getStatus());
        assertEquals(60, done.getSuccessCount() + done.getFailCount());
        assertTrue(queueService.listBatches(taskId).stream().noneMatch(b -> "PENDING".equals(b.getStatus())));
    }

    @Test
    void retryFailedData_createsNewBatchesAndSucceedsEventually() {
        String batchNo = seedData(30);
        ReportTask created = queueService.createTask(req(batchNo, 30, true), null);
        waitFinished(created.getId());
        ReportTask partial = queueService.getTask(created.getId());
        assertTrue(partial.getFailCount() > 0);

        // 重试时把单条失败率调为0，保证重试全部成功
        properties.setItemFailRate(0.0);
        queueService.retryTaskFailed(partial.getId());
        waitFor(partial.getId(), t -> "SUCCESS".equals(t.getStatus()), 20000);

        ReportTask done = queueService.getTask(partial.getId());
        assertEquals("SUCCESS", done.getStatus());
        assertEquals(30, done.getSuccessCount());
        assertEquals(0, done.getFailCount());
        assertTrue(queueService.listBatches(done.getId()).size() >= 2, "应包含原始批次+重试批次");
    }
}
