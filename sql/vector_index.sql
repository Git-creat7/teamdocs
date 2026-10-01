-- 向量索引待办
CREATE TABLE IF NOT EXISTS document_vector_task (
    document_id BIGINT NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL DEFAULT 1,
    target_signature VARCHAR(192) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_ms BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    INDEX idx_vector_task_pending (state, next_attempt_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
