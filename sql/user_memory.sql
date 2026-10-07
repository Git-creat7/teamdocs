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
