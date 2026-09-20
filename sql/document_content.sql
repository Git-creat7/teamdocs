DROP TABLE IF EXISTS document_content;

-- 文档正文切片表
CREATE TABLE document_content (
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
