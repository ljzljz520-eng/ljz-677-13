package com.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上送批次流水（每批一次HTTP请求，请求/响应全程留痕）
 */
@Data
@TableName("report_batch")
public class ReportBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String taskNo;

    /** 导入批次号 */
    private String batchNo;

    /** 批次序号，从1开始 */
    private Integer batchIndex;

    /** 本批数据ID列表（JSON数组字符串） */
    private String dataIds;

    private Integer totalCount;

    private Integer successCount;

    private Integer failCount;

    /** PENDING/RUNNING/SUCCESS/PARTIAL/FAILED */
    private String status;

    /** 国家平台HTTP状态码 */
    private Integer httpStatus;

    /** 平台返回追踪流水号 */
    private String traceId;

    /** 上送请求报文 */
    private String requestBody;

    /** 平台响应报文 */
    private String responseBody;

    /** 网络/系统级错误信息 */
    private String errorMessage;

    private Integer retryCount;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
