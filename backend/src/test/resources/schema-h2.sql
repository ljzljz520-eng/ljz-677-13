CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    real_name VARCHAR(50),
    email VARCHAR(100),
    phone VARCHAR(20),
    status TINYINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS excel_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_code VARCHAR(50) NOT NULL,
    name VARCHAR(50) NOT NULL,
    id_card VARCHAR(20),
    phone VARCHAR(20),
    amount DECIMAL(15,2),
    address VARCHAR(200),
    remark VARCHAR(500),
    batch_no VARCHAR(50) NOT NULL,
    report_status TINYINT DEFAULT 0,
    report_message VARCHAR(500),
    report_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_batch_no ON excel_data (batch_no);
CREATE INDEX IF NOT EXISTS idx_report_status ON excel_data (report_status);
CREATE INDEX IF NOT EXISTS idx_data_code ON excel_data (data_code);

CREATE TABLE IF NOT EXISTS import_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(50) NOT NULL UNIQUE,
    file_name VARCHAR(200),
    file_size BIGINT,
    total_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    status TINYINT DEFAULT 0,
    error_details CLOB,
    operator_id BIGINT,
    operator_name VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS report_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_no VARCHAR(50) NOT NULL UNIQUE,
    batch_no VARCHAR(50) NOT NULL,
    file_name VARCHAR(200),
    total_count INT DEFAULT 0,
    batch_size INT DEFAULT 100,
    total_batches INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    continue_on_fail TINYINT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(1000),
    operator_id BIGINT,
    operator_name VARCHAR(50),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS report_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    task_no VARCHAR(50) NOT NULL,
    batch_no VARCHAR(50) NOT NULL,
    batch_index INT NOT NULL,
    data_ids CLOB,
    total_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    http_status INT,
    trace_id VARCHAR(64),
    request_body CLOB,
    response_body CLOB,
    error_message VARCHAR(1000),
    retry_count INT DEFAULT 0,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
