-- 仅用于全新空库初始化。模型请求、正文与私有思维链不写入运行追踪。
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
    model_name VARCHAR(100) NOT NULL,
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

-- 每次付费尝试的预占凭证。用量未知时保留 charged_cost=reserved_cost，不退款或重放。
CREATE TABLE agent_model_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    sequence INT NOT NULL,
    quota_day DATE NOT NULL,
    estimated_input INT NOT NULL,
    max_output INT NOT NULL,
    input_price DECIMAL(20,8) NOT NULL,
    output_price DECIMAL(20,8) NOT NULL,
    reserved_cost DECIMAL(20,8) NOT NULL,
    charged_cost DECIMAL(20,8) NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    usage_known BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_model (run_id, sequence),
    INDEX idx_agent_daily_quota (user_id, quota_day),
    CHECK (reserved_cost >= 0 AND charged_cost >= 0)
);
