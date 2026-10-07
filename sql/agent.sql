
DROP TABLE IF EXISTS agent_session;
DROP TABLE IF EXISTS agent_run;
DROP TABLE IF EXISTS agent_message;
DROP TABLE IF EXISTS agent_tool_call;
DROP TABLE IF EXISTS agent_model_call;


-- 初始化
CREATE TABLE agent_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(80) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_agent_session_owner (user_id, space_id, id)
);

CREATE TABLE agent_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    scope_document_id BIGINT NULL,
    scope_folder_id BIGINT NULL,
    scope_document_ids JSON NULL,
    model_name VARCHAR(100) NOT NULL,
    model_config_ciphertext TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    error_code VARCHAR(64),
    model_calls INT NOT NULL DEFAULT 0,
    tool_calls INT NOT NULL DEFAULT 0,
    max_model_calls INT NOT NULL,
    max_tool_calls INT NOT NULL,
    max_input_tokens INT NOT NULL,
    max_output_tokens INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3),
    finished_at DATETIME(3),
    deadline_ms BIGINT NOT NULL,
    UNIQUE KEY uk_agent_request (session_id, client_request_id),
    INDEX idx_agent_active (user_id, status),
    INDEX idx_agent_deadline (status, deadline_ms)
);

CREATE TABLE agent_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    body TEXT NOT NULL,
    dependencies JSON NOT NULL,
    sources JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_message (run_id, role),
    INDEX idx_agent_history (session_id, id)
);

CREATE TABLE agent_tool_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    sequence INT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    argument_summary VARCHAR(500) NOT NULL,
    result_summary VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    duration_ms BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_tool (run_id, sequence)
);

-- 每次模型尝试的用量记录。未知用量不自动重试；调用计数与记录在同一事务内写入。
CREATE TABLE agent_model_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    sequence INT NOT NULL,
    estimated_input INT NOT NULL,
    max_output INT NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    usage_known BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_model (run_id, sequence)
);

-- 用户全局记忆增量迁移。只建新表，不重跑包含 DROP 的初始化 SQL。
CREATE TABLE IF NOT EXISTS user_memory (
    user_id BIGINT PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    items_json JSON NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS user_memory_job (
    run_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    memory_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_ms BIGINT NOT NULL DEFAULT 0,
    lease_until_ms BIGINT NOT NULL DEFAULT 0,
    claim_token VARCHAR(36),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_user_memory_job_due(status, next_attempt_ms, run_id),
    INDEX idx_user_memory_job_owner(user_id, memory_version)
);

CREATE TABLE IF NOT EXISTS user_model_config (
    user_id BIGINT PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    key_ciphertext TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_answer_feedback (
    run_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    rating VARCHAR(8) NOT NULL,
    reason VARCHAR(32),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS agent_attachment (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    run_id BIGINT NULL,
    name VARCHAR(160) NOT NULL,
    mime VARCHAR(80) NOT NULL,
    size BIGINT NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_attachment_run(run_id),
    INDEX idx_attachment_owner(user_id,run_id),
    INDEX idx_attachment_expiry(created_at)
);
