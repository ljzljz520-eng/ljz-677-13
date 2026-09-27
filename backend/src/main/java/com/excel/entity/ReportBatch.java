package com.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上送批次（每批请求/响应追踪）
 */
@Data
@TableName("report_batch")
public class ReportBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private String jobNo;

    /**
     * 关联导入批次号
     */
    private String batchNo;

    /**
     * 批次序号（从1开始）
     */
    private Integer seqNo;

    /**
     * 本批数据ID清单（逗号分隔）
     */
    private String dataIds;

    /**
     * 本批数据条数
     */
    private Integer totalCount;

    /**
     * 本批成功条数
     */
    private Integer successCount;

    /**
     * 本批失败条数
     */
    private Integer failCount;

    /**
     * 批次状态：PENDING/SENDING/SUCCESS/FAILED/CANCELLED
     */
    private String status;

    private String requestUrl;

    /**
     * 请求报文（JSON清单）
     */
    private String requestBody;

    /**
     * 响应报文
     */
    private String responseBody;

    /**
     * HTTP状态码
     */
    private Integer httpStatus;

    /**
     * 错误信息（网络异常等）
     */
    private String errorMessage;

    /**
     * 请求耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 重试次数
     */
    private Integer retryCount;

    private LocalDateTime sendTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
