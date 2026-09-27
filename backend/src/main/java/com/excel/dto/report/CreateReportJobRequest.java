package com.excel.dto.report;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 创建上送任务请求
 */
@Data
public class CreateReportJobRequest {

    /**
     * 每批上送条数，默认500，范围1~5000
     */
    @Min(value = 1, message = "每批数量不能小于1")
    @Max(value = 5000, message = "每批数量不能超过5000")
    private Integer batchSize = 500;

    /**
     * 某批失败后的策略：CONTINUE-继续后续批次 PAUSE-暂停（默认）
     */
    private String failStrategy = "PAUSE";
}
