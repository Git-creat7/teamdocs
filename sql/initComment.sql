DROP TABLE IF EXISTS comment;

CREATE TABLE comment(
    id BIGINT  AUTO_INCREMENT COMMENT '评论ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    content TEXT NOT NULL COMMENT '评论内容',
    reply_to_id BIGINT DEFAULT NULL COMMENT '回复的评论ID',
    deleted TINYINT DEFAULT 0 NOT NULL COMMENT '软删除，0未删除，1已删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_document_created (document_id, created_at, id)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '评论表';
