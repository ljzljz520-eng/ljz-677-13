package com.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上送任务
 */
@Data
@TableName("report_job")
public class ReportJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 上送任务编号
     */
    private String jobNo;

    /**
     * 关联导入批次号
     */
    private String batchNo;

    /**
     * 文件名（冗余展示）
     */
    private String fileName;

    /**
     * 待上送总数量
     */
    private Integer totalCount;

    /**
     * 待上送数量
     */
    private Integer pendingCount;

    /**
     * 上送中数量
     */
    private Integer sendingCount;

    /**
     * 成功数量
     */
    private Integer successCount;

    /**
     * 失败数量
     */
    private Integer failCount;

    /**
     * 每批数量
     */
    private Integer batchSize;

    /**
     * 总批次数
     */
    private Integer totalBatches;

    /**
     * 批次失败策略：CONTINUE-继续后续批次 PAUSE-暂停
     */
    private String failStrategy;

    /**
     * 任务状态：PENDING/SENDING/PAUSED/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED
     */
    private String status;

    /**
     * 任务结果信息
     */
    private String message;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
