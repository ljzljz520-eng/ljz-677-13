package com.excel.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.dto.ApiResponse;
import com.excel.dto.CreateReportTaskRequest;
import com.excel.dto.ReportStatusCountDTO;
import com.excel.entity.ReportBatch;
import com.excel.entity.ReportTask;
import com.excel.service.ReportQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 上送队列：任务创建（入队）、暂停/继续、重试、进度查询、批次追踪
 */
@RestController
@RequestMapping("/api/report-task")
@RequiredArgsConstructor
@Tag(name = "上送队列管理", description = "按批次异步上送国家平台的任务队列")
public class ReportTaskController {

    private static final Logger logger = LoggerFactory.getLogger(ReportTaskController.class);

    private final ReportQueueService reportQueueService;

    @PostMapping
    @Operation(summary = "创建上送任务（入队）", description = "导入成功后调用，任务异步执行，不阻塞页面")
    public ApiResponse<ReportTask> create(@RequestBody CreateReportTaskRequest request,
                                          Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReportTask task = reportQueueService.createTask(request, userId);
            return ApiResponse.success("已加入上送队列", task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            logger.error("创建上送任务失败", e);
            return ApiResponse.error("创建上送任务失败: " + e.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "上送任务列表")
    public ApiResponse<Page<ReportTask>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return ApiResponse.success(reportQueueService.pageTasks(pageNum, pageSize));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "任务详情")
    public ApiResponse<ReportTask> detail(@PathVariable Long taskId) {
        ReportTask task = reportQueueService.getTask(taskId);
        if (task == null) {
            return ApiResponse.error("任务不存在");
        }
        return ApiResponse.success(task);
    }

    @GetMapping("/{taskId}/batches")
    @Operation(summary = "任务批次列表（每批追踪信息）")
    public ApiResponse<List<ReportBatch>> batches(@PathVariable Long taskId) {
        return ApiResponse.success(reportQueueService.listBatches(taskId));
    }

    @GetMapping("/batch/{batchId}")
    @Operation(summary = "批次详情（含完整请求/响应报文）")
    public ApiResponse<ReportBatch> batchDetail(@PathVariable Long batchId) {
        ReportBatch batch = reportQueueService.getBatch(batchId);
        if (batch == null) {
            return ApiResponse.error("批次不存在");
        }
        return ApiResponse.success(batch);
    }

    @PostMapping("/{taskId}/pause")
    @Operation(summary = "暂停任务")
    public ApiResponse<Void> pause(@PathVariable Long taskId) {
        try {
            reportQueueService.pauseTask(taskId);
            return ApiResponse.success("任务已暂停", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{taskId}/resume")
    @Operation(summary = "继续任务")
    public ApiResponse<Void> resume(@PathVariable Long taskId) {
        try {
            reportQueueService.resumeTask(taskId);
            return ApiResponse.success("任务已继续", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{taskId}/retry-failed")
    @Operation(summary = "重试任务中失败数据")
    public ApiResponse<ReportTask> retryFailed(@PathVariable Long taskId) {
        try {
            ReportTask task = reportQueueService.retryTaskFailed(taskId);
            return ApiResponse.success("失败数据已重新入队", task);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/batch/{batchId}/retry")
    @Operation(summary = "重试单个批次")
    public ApiResponse<Void> retryBatch(@PathVariable Long batchId) {
        try {
            reportQueueService.retryBatch(batchId);
            return ApiResponse.success("批次已重新入队", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/status/{batchNo}")
    @Operation(summary = "按导入批次统计待上送/上送中/成功/失败数量")
    public ApiResponse<ReportStatusCountDTO> statusCount(@PathVariable String batchNo) {
        return ApiResponse.success(reportQueueService.getStatusCount(batchNo));
    }

    @GetMapping("/by-batch/{batchNo}")
    @Operation(summary = "按导入批次号查询最新上送任务")
    public ApiResponse<ReportTask> byBatchNo(@PathVariable String batchNo) {
        return ApiResponse.success(reportQueueService.getTaskByBatchNo(batchNo));
    }
}
