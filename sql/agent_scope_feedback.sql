-- 已有数据库备份后执行一次，不修改旧标签表和数据。
ALTER TABLE agent_run ADD COLUMN scope_document_id BIGINT NULL,
    ADD COLUMN scope_folder_id BIGINT NULL,
    ADD COLUMN scope_document_ids JSON NULL;
CREATE TABLE IF NOT EXISTS agent_answer_feedback (
    run_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    rating VARCHAR(8) NOT NULL,
    reason VARCHAR(32),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
