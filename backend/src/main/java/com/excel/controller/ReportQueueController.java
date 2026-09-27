package com.excel.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.dto.ApiResponse;
import com.excel.dto.report.CreateReportJobRequest;
import com.excel.entity.ReportBatch;
import com.excel.entity.ReportJob;
import com.excel.entity.User;
import com.excel.mapper.UserMapper;
import com.excel.service.ReportQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 上送队列：导入成功后加入队列，异步分批上送国家平台
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
@Tag(name = "上送队列管理", description = "分批异步上送国家平台及批次追踪")
public class ReportQueueController {

    private static final Logger logger = LoggerFactory.getLogger(ReportQueueController.class);

    private final ReportQueueService reportQueueService;
    private final UserMapper userMapper;

    @PostMapping("/jobs/{batchNo}")
    @Operation(summary = "加入上送队列", description = "将指定导入批次的待上送数据加入队列，按批次异步上送")
    public ApiResponse<ReportJob> createJob(@PathVariable String batchNo,
                                            @Valid @RequestBody(required = false) CreateReportJobRequest request,
                                            Authentication authentication) {
        try {
            CreateReportJobRequest body = request != null ? request : new CreateReportJobRequest();
            Long userId = (Long) authentication.getPrincipal();
            User user = userMapper.selectById(userId);
            String operatorName = user != null ? user.getRealName() : "系统";
            ReportJob job = reportQueueService.createJob(batchNo, body, userId, operatorName);
            return ApiResponse.success("已加入上送队列", job);
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            logger.error("创建上送任务失败", e);
            return ApiResponse.error("创建上送任务失败: " + e.getMessage());
        }
    }

    @GetMapping("/jobs")
    @Operation(summary = "上送任务列表", description = "分页查询上送任务")
    public ApiResponse<Page<ReportJob>> pageJobs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(reportQueueService.pageJobs(pageNum, pageSize, status));
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "上送任务详情", description = "获取任务及各数量统计")
    public ApiResponse<ReportJob> getJob(@PathVariable Long jobId) {
        ReportJob job = reportQueueService.getJob(jobId);
        if (job == null) {
            return ApiResponse.error("任务不存在");
        }
        return ApiResponse.success(job);
    }

    @GetMapping("/jobs/{jobId}/batches")
    @Operation(summary = "任务批次列表", description = "获取任务下所有批次及状态，用于逐批追踪")
    public ApiResponse<List<ReportBatch>> listBatches(@PathVariable Long jobId) {
        return ApiResponse.success(reportQueueService.listBatches(jobId));
    }

    @GetMapping("/batches/{batchId}")
    @Operation(summary = "批次追踪详情", description = "查看单批的请求报文和响应报文")
    public ApiResponse<ReportBatch> getBatch(@PathVariable Long batchId) {
        ReportBatch batch = reportQueueService.getBatch(batchId);
        if (batch == null) {
            return ApiResponse.error("批次不存在");
        }
        return ApiResponse.success(batch);
    }

    @PostMapping("/jobs/{jobId}/pause")
    @Operation(summary = "暂停任务", description = "当前批次执行完后暂停，后续批次不再发送")
    public ApiResponse<Void> pause(@PathVariable Long jobId) {
        try {
            reportQueueService.pause(jobId);
            return ApiResponse.success("任务将在当前批次完成后暂停", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/jobs/{jobId}/resume")
    @Operation(summary = "继续任务", description = "继续上送剩余待上送批次")
    public ApiResponse<Void> resume(@PathVariable Long jobId) {
        try {
            reportQueueService.resume(jobId);
            return ApiResponse.success("任务继续上送", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/jobs/{jobId}/retry")
    @Operation(summary = "重试失败批次", description = "重置失败数据并重新上送失败批次")
    public ApiResponse<Void> retry(@PathVariable Long jobId) {
        try {
            reportQueueService.retry(jobId);
            return ApiResponse.success("开始重试失败批次", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "取消任务", description = "取消未完成批次，已上送数据不回滚")
    public ApiResponse<Void> cancel(@PathVariable Long jobId) {
        try {
            reportQueueService.cancel(jobId);
            return ApiResponse.success("任务已取消", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
