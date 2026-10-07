CREATE TABLE IF NOT EXISTS user_model_config (
    user_id BIGINT PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    key_ciphertext TEXT NOT NULL
);

ALTER TABLE agent_run ADD COLUMN model_config_ciphertext TEXT NULL AFTER model_name;
