package com.excel.controller;

import com.excel.config.ReportQueueProperties;
import com.excel.dto.mock.NationalReportRequest;
import com.excel.dto.mock.NationalReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 国家平台模拟接口。
 * 模拟真实平台行为：
 * - 一定概率平台系统异常（HTTP 500）
 * - 一定概率整批校验拒收（HTTP 400，全部条目失败）
 * - 其余情况下逐条校验，按概率返回单条失败（HTTP 200 或 400）
 */
@RestController
@RequestMapping("/api/mock/national")
@RequiredArgsConstructor
@Tag(name = "国家平台模拟接口", description = "模拟国家平台数据接收接口")
public class MockNationalPlatformController {

    private static final Logger logger = LoggerFactory.getLogger(MockNationalPlatformController.class);

    private static final String[] ERROR_MESSAGES = {
            "国家平台返回：数据格式不符合规范",
            "国家平台返回：重复数据已存在",
            "国家平台返回：身份证号校验失败",
            "国家平台返回：手机号格式错误",
            "国家平台返回：金额超出限额",
            "国家平台返回：必填字段缺失"
    };

    private final ReportQueueProperties properties;

    @PostMapping("/report")
    @Operation(summary = "数据上送接口（模拟）")
    public ResponseEntity<NationalReportResponse> receive(@RequestBody NationalReportRequest request) {
        String traceId = "NP" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // 模拟平台处理耗时
        long latency = ThreadLocalRandom.current().nextLong(
                properties.getLatencyMinMs(), properties.getLatencyMaxMs() + 1);
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int itemCount = request.getItems() == null ? 0 : request.getItems().size();
        logger.info("[模拟国家平台] 收到上送: taskNo={}, batch={}/{}, 条数={}, traceId={}",
                request.getTaskNo(), request.getBatchNo(), request.getBatchIndex(), itemCount, traceId);

        // 1. 平台系统异常 HTTP 500
        if (random.nextDouble() < properties.getBatchErrorRate()) {
            NationalReportResponse resp = baseResponse(500, "国家平台系统繁忙，请稍后重试", traceId);
            logger.warn("[模拟国家平台] 系统异常 traceId={}", traceId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resp);
        }

        List<NationalReportResponse.ItemResult> results = new ArrayList<>();
        boolean wholeRejected = random.nextDouble() < properties.getBatchFailRate();
        int failCount = 0;

        for (NationalReportRequest.Item item : request.getItems()) {
            NationalReportResponse.ItemResult r = new NationalReportResponse.ItemResult();
            r.setId(item.getId());
            r.setDataCode(item.getDataCode());

            boolean fail = wholeRejected || random.nextDouble() < properties.getItemFailRate();
            if (fail) {
                r.setSuccess(false);
                if (wholeRejected) {
                    r.setErrorMsg("国家平台返回：本批数据未通过整体校验，整批拒收");
                } else {
                    r.setErrorMsg(ERROR_MESSAGES[random.nextInt(ERROR_MESSAGES.length)]);
                }
                failCount++;
            } else {
                r.setSuccess(true);
            }
            results.add(r);
        }

        NationalReportResponse resp = baseResponse(
                failCount == 0 ? 200 : 400,
                failCount == 0 ? "受理成功" : "部分数据校验失败",
                traceId);
        resp.setResults(results);

        HttpStatus httpStatus = failCount == 0 ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        logger.info("[模拟国家平台] 处理完成 traceId={}, 成功={}, 失败={}",
                traceId, itemCount - failCount, failCount);
        return ResponseEntity.status(httpStatus).body(resp);
    }

    private NationalReportResponse baseResponse(int code, String message, String traceId) {
        NationalReportResponse resp = new NationalReportResponse();
        resp.setCode(code);
        resp.setMessage(message);
        resp.setTraceId(traceId);
        return resp;
    }
}
