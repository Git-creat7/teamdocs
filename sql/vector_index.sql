-- ============================================================================
-- TeamDocs 增量迁移脚本 2：向量索引蓝绿切换、租约与历史数据回填
-- 适用对象：全新数据库或已运行的存量 MySQL 8.0+ 数据库（支持重复执行保护 / 幂等执行）
-- ============================================================================
-- 迁移策略、蓝绿切换顺序与失败恢复说明：
-- 1. 历史向量重建期间的降级策略：
--    - 存量已完成的 DONE 任务在步骤 3 中将 active_generation 初始化为当前 generation，
--      active_parse_version 初始化为 document.parse_version，active_collection 初始化为 'teamdocs_chunks'。
--    - 当因补充章节标题或切换 HNSW 触发后台重建时，VectorIndexWorker 会创建独立的新集合
--      （teamdocs_chunks_d{docId}_g{newGen}）写入新向量；在新集合完成写入、验证与切换前，
--      检索继续读取原 active_collection（'teamdocs_chunks'），若期间文档发生重解析（parse_version 递增），
--      HybridChunkSearch 自动过滤版本不一致的旧向量并平滑降级为 ES/MySQL 词法检索。
-- 2. 新旧集合切换顺序（蓝绿隔离）：
--    (a) 创建新集合 teamdocs_chunks_d{docId}_g{gen} 及 COSINE 索引并加载；
--    (b) 分批（<=16）写入全部切片向量，统计 writtenCount == rows.size()；
--    (c) 执行 verifyCollection：校验集合 schema、向量维度、COSINE 索引结构及抽样查询命中；
--    (d) 执行 MySQL 条件原子更新 completeAndSwitch（校验 generation、claim_token、state='PENDING'、
--        lease_until_ms > nowMs、target_parse_version），将旧 active_collection 移入 stale_collection，
--        并将 active_collection 切换为新集合、cleanup_pending 置为 1；
--    (e) 切换成功后延迟清理 stale_collection（独立集合执行 dropCollection，默认集合执行 deleteDocument），
--        清理成功后重置 cleanup_pending=0；若清理失败，由 reconcile 定时补偿。
-- 3. 每一步迁移失败后的恢复方式：
--    - DDL 扩展列或索引中途失败：所有 ALTER TABLE 均受 information_schema 幂等保护，直接重跑本脚本即可恢复。
--    - 历史数据回填中断：UPDATE 语句均带幂等条件（target_parse_version IS NULL / active_generation = 0），
--      直接重跑本脚本即可完成剩余记录回填。
--    - 后台向量蓝绿重建中断：未切换的新集合不会影响 active_collection，租约到期后 Worker 自动重试或由 reconcile 补扫。
-- ============================================================================

-- 1. 创建表（全新环境直接具备完整结构）
CREATE TABLE IF NOT EXISTS document_vector_task (
    document_id BIGINT NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL DEFAULT 1,
    target_signature VARCHAR(192) NOT NULL,
    target_parse_version INT DEFAULT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_ms BIGINT NOT NULL DEFAULT 0,
    lease_until_ms BIGINT NOT NULL DEFAULT 0,
    claim_token VARCHAR(36) DEFAULT NULL,
    active_generation BIGINT NOT NULL DEFAULT 0,
    active_parse_version INT DEFAULT NULL,
    active_collection VARCHAR(128) DEFAULT NULL,
    stale_collection VARCHAR(128) DEFAULT NULL,
    active_chunk_count INT NOT NULL DEFAULT 0,
    cleanup_pending TINYINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    INDEX idx_vector_task_pending (state, next_attempt_ms),
    INDEX idx_vector_task_lease (state, lease_until_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 存量表幂等补充新增列与索引（兼容已运行旧版 document_vector_task 的环境）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'target_parse_version');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN target_parse_version INT DEFAULT NULL AFTER target_signature', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'lease_until_ms');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN lease_until_ms BIGINT NOT NULL DEFAULT 0 AFTER next_attempt_ms', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'claim_token');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN claim_token VARCHAR(36) DEFAULT NULL AFTER lease_until_ms', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'active_generation');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN active_generation BIGINT NOT NULL DEFAULT 0 AFTER claim_token', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'active_parse_version');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN active_parse_version INT DEFAULT NULL AFTER active_generation', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'active_collection');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN active_collection VARCHAR(128) DEFAULT NULL AFTER active_parse_version', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'stale_collection');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN stale_collection VARCHAR(128) DEFAULT NULL AFTER active_collection', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'active_chunk_count');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN active_chunk_count INT NOT NULL DEFAULT 0 AFTER stale_collection', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND COLUMN_NAME = 'cleanup_pending');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_vector_task ADD COLUMN cleanup_pending TINYINT NOT NULL DEFAULT 0 AFTER active_chunk_count', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vector_task' AND INDEX_NAME = 'idx_vector_task_lease');
SET @ddl = IF(@idx_exists = 0, 'ALTER TABLE document_vector_task ADD INDEX idx_vector_task_lease (state, lease_until_ms)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. 历史数据回填：回填 target_parse_version，并初始化已完成（DONE）任务的 active_generation、active_parse_version 与 active_collection
UPDATE document_vector_task t
INNER JOIN document d ON d.id = t.document_id
SET t.target_parse_version = d.parse_version
WHERE t.target_parse_version IS NULL AND t.target_signature <> 'deleted';

UPDATE document_vector_task t
INNER JOIN document d ON d.id = t.document_id
SET t.active_generation = t.generation,
    t.active_parse_version = d.parse_version,
    t.active_collection = COALESCE(t.active_collection, 'teamdocs_qwen3_8b_d1024_v1')
WHERE t.state = 'DONE'
  AND t.active_generation = 0
  AND t.target_signature <> 'deleted'
  AND d.deleted = 0
  AND d.parse_status = 'READY';

SET @has_doc_content = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_content');
SET @ddl = IF(@has_doc_content > 0,
    'UPDATE document_vector_task t SET t.active_chunk_count = (SELECT COUNT(*) FROM document_content c WHERE c.document_id = t.document_id) WHERE t.state = ''DONE'' AND t.active_generation > 0 AND t.active_chunk_count = 0',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
