package com.excel.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 上送状态数量统计：待上送、上送中、成功、失败
 */
@Data
@Builder
public class ReportStatusCountDTO {
    private String batchNo;
    private Long pending;
    private Long sending;
    private Long success;
    private Long failed;
    private Long total;
}
