package com.excel.dto;

import lombok.Data;

/**
 * 创建上送任务请求
 */
@Data
public class CreateReportTaskRequest {

    /** 导入批次号 */
    private String batchNo;

    /** 每批条数，为空使用全局默认配置 */
    private Integer batchSize;

    /** 某批失败后是否继续后续批次，为空使用全局默认配置 */
    private Boolean continueOnFail;

    /**
     * 上送范围：
     * null/ALL-全部待上送数据；FAILED_ONLY-仅失败数据（重试）
     */
    private String scope;
}
