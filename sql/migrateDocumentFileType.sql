-- Existing databases only. Fresh databases use the definition in initDocument.sql.
ALTER TABLE document
    MODIFY COLUMN file_type VARCHAR(255) DEFAULT NULL COMMENT '文件 MIME 类型';
