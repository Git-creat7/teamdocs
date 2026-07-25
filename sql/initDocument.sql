DROP TABLE IF EXISTS document_tag;
DROP TABLE IF EXISTS folder;
DROP TABLE IF EXISTS document;
DROP TABLE IF EXISTS tag;

-- 文件夹表
CREATE TABLE folder (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    space_id   BIGINT       NOT NULL COMMENT '所属空间',
    parent_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '父文件夹ID，0表示根目录',
    name       VARCHAR(128) NOT NULL COMMENT '文件夹名',
    created_by BIGINT       NOT NULL COMMENT '创建者ID',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_space_parent (space_id, parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件夹表';

-- 文档表
CREATE TABLE document (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    space_id    BIGINT       NOT NULL COMMENT '所属空间',
    folder_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '所属文件夹，0表示根目录',
    name        VARCHAR(255) NOT NULL COMMENT '文档显示名',
    file_type   VARCHAR(32)  DEFAULT NULL COMMENT '文件类型（pdf/docx/md/png等）',
    file_size   BIGINT       DEFAULT NULL COMMENT '文件大小（字节）',
    file_path   VARCHAR(512) NOT NULL COMMENT '存储路径',
    description VARCHAR(512) DEFAULT NULL COMMENT '文档描述',
    upload_by   BIGINT       NOT NULL COMMENT '上传者ID',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除，0未删除，1已删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_space_folder_updated (space_id, folder_id, deleted, updated_at, id),
    KEY idx_space_deleted_updated (space_id, deleted, updated_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文档表';

-- 标签表
CREATE TABLE tag (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    space_id   BIGINT      NOT NULL COMMENT '所属空间',
    name       VARCHAR(64) NOT NULL COMMENT '标签名',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_name (space_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '标签表';

-- 文档-标签关联表
CREATE TABLE document_tag (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    tag_id      BIGINT NOT NULL COMMENT '标签ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_tag (document_id, tag_id),
    KEY idx_tag_document (tag_id, document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文档标签关联表';
