-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密）',
    `real_name` VARCHAR(50) COMMENT '真实姓名',
    `email` VARCHAR(100) COMMENT '邮箱',
    `phone` VARCHAR(20) COMMENT '手机号',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- Excel数据表
CREATE TABLE IF NOT EXISTS `excel_data` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `data_code` VARCHAR(50) NOT NULL COMMENT '数据编号',
    `name` VARCHAR(50) NOT NULL COMMENT '姓名',
    `id_card` VARCHAR(20) COMMENT '身份证号',
    `phone` VARCHAR(20) COMMENT '手机号',
    `amount` DECIMAL(15,2) COMMENT '金额',
    `address` VARCHAR(200) COMMENT '地址',
    `remark` VARCHAR(500) COMMENT '备注',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '导入批次号',
    `report_status` TINYINT DEFAULT 0 COMMENT '上报状态：0-待上报 1-已上报 2-上报失败 3-上送中',
    `report_message` VARCHAR(500) COMMENT '上报结果信息',
    `report_time` DATETIME COMMENT '上报时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    INDEX `idx_batch_no` (`batch_no`),
    INDEX `idx_report_status` (`report_status`),
    INDEX `idx_data_code` (`data_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Excel数据表';

-- 导入记录表
CREATE TABLE IF NOT EXISTS `import_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '批次号',
    `file_name` VARCHAR(200) COMMENT '文件名',
    `file_size` BIGINT COMMENT '文件大小（字节）',
    `total_count` INT DEFAULT 0 COMMENT '总记录数',
    `success_count` INT DEFAULT 0 COMMENT '成功数量',
    `fail_count` INT DEFAULT 0 COMMENT '失败数量',
    `status` TINYINT DEFAULT 0 COMMENT '导入状态：0-处理中 1-完成 2-失败',
    `error_details` TEXT COMMENT '错误详情',
    `operator_id` BIGINT COMMENT '操作人ID',
    `operator_name` VARCHAR(50) COMMENT '操作人姓名',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_no` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入记录表';

-- 上送任务表（一个导入批次加入队列后生成一个任务）
CREATE TABLE IF NOT EXISTS `report_job` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `job_no` VARCHAR(64) NOT NULL COMMENT '上送任务编号',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '关联导入批次号',
    `file_name` VARCHAR(200) COMMENT '文件名（冗余展示）',
    `total_count` INT DEFAULT 0 COMMENT '待上送总数量',
    `pending_count` INT DEFAULT 0 COMMENT '待上送数量',
    `sending_count` INT DEFAULT 0 COMMENT '上送中数量',
    `success_count` INT DEFAULT 0 COMMENT '成功数量',
    `fail_count` INT DEFAULT 0 COMMENT '失败数量',
    `batch_size` INT DEFAULT 500 COMMENT '每批数量',
    `total_batches` INT DEFAULT 0 COMMENT '总批次数',
    `fail_strategy` VARCHAR(20) DEFAULT 'PAUSE' COMMENT '批次失败策略：CONTINUE-继续后续批次 PAUSE-暂停',
    `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '任务状态：PENDING-待运行 SENDING-上送中 PAUSED-已暂停 SUCCESS-全部成功 PARTIAL_SUCCESS-部分成功 FAILED-失败 CANCELLED-已取消',
    `message` VARCHAR(500) COMMENT '任务结果信息',
    `operator_id` BIGINT COMMENT '操作人ID',
    `operator_name` VARCHAR(50) COMMENT '操作人姓名',
    `start_time` DATETIME COMMENT '开始执行时间',
    `end_time` DATETIME COMMENT '结束时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_job_no` (`job_no`),
    INDEX `idx_rj_batch_no` (`batch_no`),
    INDEX `idx_rj_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上送任务表';

-- 上送批次表（每个任务拆分为多个批次，逐批请求国家平台并留存报文）
CREATE TABLE IF NOT EXISTS `report_batch` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `job_id` BIGINT NOT NULL COMMENT '上送任务ID',
    `job_no` VARCHAR(64) NOT NULL COMMENT '上送任务编号',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '关联导入批次号',
    `seq_no` INT NOT NULL COMMENT '批次序号（从1开始）',
    `data_ids` TEXT COMMENT '本批数据ID清单（逗号分隔）',
    `total_count` INT DEFAULT 0 COMMENT '本批数据条数',
    `success_count` INT DEFAULT 0 COMMENT '本批成功条数',
    `fail_count` INT DEFAULT 0 COMMENT '本批失败条数',
    `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '批次状态：PENDING-待上送 SENDING-上送中 SUCCESS-成功 FAILED-失败 CANCELLED-已取消',
    `request_url` VARCHAR(500) COMMENT '请求地址',
    `request_body` MEDIUMTEXT COMMENT '请求报文（JSON清单）',
    `response_body` MEDIUMTEXT COMMENT '响应报文（国家平台返回JSON）',
    `http_status` INT COMMENT 'HTTP状态码',
    `error_message` VARCHAR(1000) COMMENT '错误信息（网络异常等）',
    `duration_ms` BIGINT COMMENT '请求耗时（毫秒）',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `send_time` DATETIME COMMENT '上送时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    INDEX `idx_rb_job_id` (`job_id`),
    INDEX `idx_rb_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上送批次追踪表';

-- 管理员用户由应用启动时通过 DataInitializer 自动创建
-- 账号: admin  密码: admin123
