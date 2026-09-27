package com.excel.controller;

import com.excel.dto.report.NationalReportRequest;
import com.excel.dto.report.NationalReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 国家平台模拟接口
 * <p>
 * 模拟国家平台批量接收行为：
 * - 约 8% 概率整批拒收（HTTP 200，success=false）
 * - 约 3% 概率平台内部错误（HTTP 500）
 * - 正常情况下单条数据约 5% 概率校验失败
 * - 每次请求有随机处理耗时
 */
@RestController
@RequestMapping("/api/mock/national")
@Tag(name = "国家平台模拟", description = "模拟国家平台批量接收接口")
public class MockNationalPlatformController {

    private static final Logger logger = LoggerFactory.getLogger(MockNationalPlatformController.class);

    private final Random random = new Random();

    private static final String[] ITEM_ERRORS = {
            "数据格式不符合规范",
            "重复数据已存在",
            "身份证号校验失败",
            "手机号格式错误",
            "金额超出限额",
            "数据主键冲突",
            "必填字段缺失"
    };

    @PostMapping("/report")
    @Operation(summary = "批量接收数据（模拟）", description = "模拟国家平台批量清单接收接口")
    public NationalReportResponse receive(@RequestBody NationalReportRequest request) throws InterruptedException {
        // 模拟平台处理耗时 200~800ms
        Thread.sleep(200 + random.nextInt(600));

        int itemCount = request.getItems() == null ? 0 : request.getItems().size();
        logger.info("[国家平台模拟] 接收任务{} 第{}批，共{}条", request.getJobNo(), request.getSeqNo(), itemCount);

        NationalReportResponse response = new NationalReportResponse();
        response.setReceiptNo("RC" + System.currentTimeMillis() + random.nextInt(1000));

        // 约3%概率平台内部错误
        if (random.nextInt(100) < 3) {
            response.setSuccess(false);
            response.setCode("500");
            response.setMessage("国家平台内部错误：服务暂时不可用");
            response.setResults(new ArrayList<>());
            // 通过标记 success=false 模拟500场景由客户端抛出；此处直接返回报文
            return response;
        }

        List<NationalReportResponse.ItemResult> results = new ArrayList<>();

        // 约8%概率整批拒收（例如清单签名错误/批次号重复）
        if (random.nextInt(100) < 8) {
            response.setSuccess(false);
            response.setCode("BATCH_REJECTED");
            response.setMessage("整批拒收：批次清单校验未通过");
            for (NationalReportRequest.NationalReportItem item : request.getItems()) {
                results.add(NationalReportResponse.ItemResult.of(
                        item.getId(), item.getDataCode(), false, "整批拒收：批次清单校验未通过"));
            }
            response.setResults(results);
            return response;
        }

        // 正常处理：单条约5%失败率
        int failCount = 0;
        for (NationalReportRequest.NationalReportItem item : request.getItems()) {
            if (random.nextInt(100) < 5) {
                failCount++;
                results.add(NationalReportResponse.ItemResult.of(
                        item.getId(), item.getDataCode(), false,
                        ITEM_ERRORS[random.nextInt(ITEM_ERRORS.length)]));
            } else {
                results.add(NationalReportResponse.ItemResult.of(
                        item.getId(), item.getDataCode(), true, "接收成功"));
            }
        }

        response.setSuccess(failCount == 0);
        response.setCode(failCount == 0 ? "0" : "PARTIAL_FAIL");
        response.setMessage(failCount == 0 ? "全部接收成功" : "部分数据校验失败");
        response.setResults(results);
        return response;
    }
}
