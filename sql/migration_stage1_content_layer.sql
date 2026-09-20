-- ==============================================================================
-- TeamDocs AI 改造 - Stage 1: 文档正文数据层 增量迁移脚本
-- 用于已有 MySQL 8.0 数据库实例的平滑升级（无需重置或丢失现有数据）
-- ==============================================================================

-- 1. 为已有 document 表增补解析状态与切片元数据字段（MySQL 8.0 支持 IF NOT EXISTS）
ALTER TABLE document
    ADD COLUMN IF NOT EXISTS parse_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '解析状态: PENDING, PARSING, READY, FAILED' AFTER deleted,
    ADD COLUMN IF NOT EXISTS chunk_count  INT          NOT NULL DEFAULT 0 COMMENT '生成的Chunk数量' AFTER parse_status,
    ADD COLUMN IF NOT EXISTS parse_error  VARCHAR(512) DEFAULT NULL COMMENT '解析失败异常信息' AFTER chunk_count,
    ADD COLUMN IF NOT EXISTS parsed_at    DATETIME     DEFAULT NULL COMMENT '完成解析时间' AFTER parse_error;

-- 2. 创建文档切片内容表 document_content
CREATE TABLE IF NOT EXISTS document_content (
    id          BIGINT     NOT NULL AUTO_INCREMENT,
    document_id BIGINT     NOT NULL COMMENT '关联文档ID',
    space_id    BIGINT     NOT NULL COMMENT '所属空间ID',
    chunk_index INT        NOT NULL COMMENT '分块序号，从0开始递增',
    content     MEDIUMTEXT NOT NULL COMMENT '正文分块文本内容',
    token_count INT        NOT NULL DEFAULT 0 COMMENT '分块预估Token数或字符数',
    created_at  DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_chunk (document_id, chunk_index),
    KEY idx_space_id (space_id),
    KEY idx_document_id (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文档正文切片表';
