package com.excel.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等建表（兼容已存在的MySQL数据卷：docker-entrypoint-initdb.d 仅在空库时执行）
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class TableInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(TableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    private static final String REPORT_JOB_DDL = """
            CREATE TABLE IF NOT EXISTS `report_job` (
                `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                `job_no` VARCHAR(64) NOT NULL COMMENT '上送任务编号',
                `batch_no` VARCHAR(50) NOT NULL COMMENT '关联导入批次号',
                `file_name` VARCHAR(200) COMMENT '文件名',
                `total_count` INT DEFAULT 0 COMMENT '待上送总数量',
                `pending_count` INT DEFAULT 0 COMMENT '待上送数量',
                `sending_count` INT DEFAULT 0 COMMENT '上送中数量',
                `success_count` INT DEFAULT 0 COMMENT '成功数量',
                `fail_count` INT DEFAULT 0 COMMENT '失败数量',
                `batch_size` INT DEFAULT 500 COMMENT '每批数量',
                `total_batches` INT DEFAULT 0 COMMENT '总批次数',
                `fail_strategy` VARCHAR(20) DEFAULT 'PAUSE' COMMENT '批次失败策略',
                `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '任务状态',
                `message` VARCHAR(500) COMMENT '任务结果信息',
                `operator_id` BIGINT COMMENT '操作人ID',
                `operator_name` VARCHAR(50) COMMENT '操作人姓名',
                `start_time` DATETIME COMMENT '开始执行时间',
                `end_time` DATETIME COMMENT '结束时间',
                `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                `deleted` TINYINT DEFAULT 0 COMMENT '是否删除',
                PRIMARY KEY (`id`),
                UNIQUE KEY `uk_job_no` (`job_no`),
                INDEX `idx_rj_batch_no` (`batch_no`),
                INDEX `idx_rj_status` (`status`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上送任务表'
            """;

    private static final String REPORT_BATCH_DDL = """
            CREATE TABLE IF NOT EXISTS `report_batch` (
                `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                `job_id` BIGINT NOT NULL COMMENT '上送任务ID',
                `job_no` VARCHAR(64) NOT NULL COMMENT '上送任务编号',
                `batch_no` VARCHAR(50) NOT NULL COMMENT '关联导入批次号',
                `seq_no` INT NOT NULL COMMENT '批次序号',
                `data_ids` TEXT COMMENT '本批数据ID清单（逗号分隔）',
                `total_count` INT DEFAULT 0 COMMENT '本批数据条数',
                `success_count` INT DEFAULT 0 COMMENT '本批成功条数',
                `fail_count` INT DEFAULT 0 COMMENT '本批失败条数',
                `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '批次状态',
                `request_url` VARCHAR(500) COMMENT '请求地址',
                `request_body` MEDIUMTEXT COMMENT '请求报文',
                `response_body` MEDIUMTEXT COMMENT '响应报文',
                `http_status` INT COMMENT 'HTTP状态码',
                `error_message` VARCHAR(1000) COMMENT '错误信息',
                `duration_ms` BIGINT COMMENT '请求耗时毫秒',
                `retry_count` INT DEFAULT 0 COMMENT '重试次数',
                `send_time` DATETIME COMMENT '上送时间',
                `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                `deleted` TINYINT DEFAULT 0 COMMENT '是否删除',
                PRIMARY KEY (`id`),
                INDEX `idx_rb_job_id` (`job_id`),
                INDEX `idx_rb_status` (`status`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上送批次追踪表'
            """;

    @Override
    public void run(String... args) {
        jdbcTemplate.execute(REPORT_JOB_DDL);
        jdbcTemplate.execute(REPORT_BATCH_DDL);
        logger.info("上送队列表结构检查完成");
    }
}
