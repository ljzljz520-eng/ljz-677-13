package com.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上送任务（队列中的一个任务，对应一个导入批次）
 */
@Data
@TableName("report_task")
public class ReportTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上送任务编号 */
    private String taskNo;

    /** 导入批次号 */
    private String batchNo;

    /** 文件名 */
    private String fileName;

    /** 待上送数据总条数 */
    private Integer totalCount;

    /** 每批条数 */
    private Integer batchSize;

    /** 总批次数 */
    private Integer totalBatches;

    /** 成功数量 */
    private Integer successCount;

    /** 失败数量 */
    private Integer failCount;

    /** 某批失败后是否继续：0-暂停 1-继续 */
    private Integer continueOnFail;

    /** PENDING/RUNNING/PAUSED/SUCCESS/PARTIAL/FAILED/CANCELLED */
    private String status;

    private String errorMessage;

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
