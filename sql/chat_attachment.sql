-- 仅新增对话附件表，不修改业务文档或原有数据。
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
