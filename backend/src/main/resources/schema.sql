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
    `report_status` TINYINT DEFAULT 0 COMMENT '上报状态：0-待上报 1-已上报 2-上报失败',
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

-- 上送任务表（上送队列）
CREATE TABLE IF NOT EXISTS `report_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_no` VARCHAR(50) NOT NULL COMMENT '上送任务编号',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '导入批次号',
    `file_name` VARCHAR(200) COMMENT '文件名',
    `total_count` INT DEFAULT 0 COMMENT '待上送数据总条数',
    `batch_size` INT DEFAULT 100 COMMENT '每批条数',
    `total_batches` INT DEFAULT 0 COMMENT '总批次数',
    `success_count` INT DEFAULT 0 COMMENT '成功数量',
    `fail_count` INT DEFAULT 0 COMMENT '失败数量',
    `continue_on_fail` TINYINT DEFAULT 0 COMMENT '某批失败后是否继续后续批次：0-暂停 1-继续',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/PAUSED/SUCCESS/PARTIAL/FAILED/CANCELLED',
    `error_message` VARCHAR(1000) COMMENT '任务级错误信息',
    `operator_id` BIGINT COMMENT '操作人ID',
    `operator_name` VARCHAR(50) COMMENT '操作人姓名',
    `start_time` DATETIME COMMENT '开始上送时间',
    `end_time` DATETIME COMMENT '结束时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    INDEX `rt_idx_batch_no` (`batch_no`),
    INDEX `rt_idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上送任务表';

-- 上送批次表（每批请求与响应均可追踪）
CREATE TABLE IF NOT EXISTS `report_batch` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` BIGINT NOT NULL COMMENT '上送任务ID',
    `task_no` VARCHAR(50) NOT NULL COMMENT '上送任务编号',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '导入批次号',
    `batch_index` INT NOT NULL COMMENT '批次序号（从1开始）',
    `data_ids` TEXT COMMENT '本批数据ID列表（JSON数组）',
    `total_count` INT DEFAULT 0 COMMENT '本批总条数',
    `success_count` INT DEFAULT 0 COMMENT '成功数量',
    `fail_count` INT DEFAULT 0 COMMENT '失败数量',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/SUCCESS/PARTIAL/FAILED',
    `http_status` INT COMMENT '国家平台HTTP状态码',
    `trace_id` VARCHAR(64) COMMENT '国家平台返回的追踪流水号',
    `request_body` LONGTEXT COMMENT '上送请求报文',
    `response_body` LONGTEXT COMMENT '平台响应报文',
    `error_message` VARCHAR(1000) COMMENT '错误信息（网络异常等）',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `start_time` DATETIME COMMENT '开始上送时间',
    `end_time` DATETIME COMMENT '结束时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    INDEX `rb_idx_task_id` (`task_id`),
    INDEX `rb_idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上送批次流水表';

-- 管理员用户由应用启动时通过 DataInitializer 自动创建
-- 账号: admin  密码: admin123
