-- 空数据库首次初始化入口。包含 DROP，禁止在已有业务数据库上重复执行。
-- 顺序：用户、空间、文档、评论、日志、全文索引、正文、Agent、向量待办。

-- 用户
DROP TABLE IF EXISTS user;
CREATE TABLE user(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(50),
    email VARCHAR(100) UNIQUE,
    avatar VARCHAR(255),
    status TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 空间与成员
DROP TABLE IF EXISTS space;
CREATE TABLE space(
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '空间名称',
    description VARCHAR(255) DEFAULT NULL COMMENT '空间描述',
    owner_id BIGINT NOT NULL COMMENT '空间创建者ID',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除，0表示未删除，1表示已删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='空间表';

DROP TABLE IF EXISTS space_member;
CREATE TABLE space_member(
    id BIGINT NOT NULL AUTO_INCREMENT,
    space_id BIGINT NOT NULL COMMENT '空间ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(16) NOT NULL COMMENT '成员角色，如owner、admin、member',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_user (space_id, user_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='空间成员表';

-- 文件夹、文档和标签
DROP TABLE IF EXISTS document_tag;
DROP TABLE IF EXISTS folder;
DROP TABLE IF EXISTS document;
DROP TABLE IF EXISTS tag;

CREATE TABLE folder (
    id BIGINT NOT NULL AUTO_INCREMENT,
    space_id BIGINT NOT NULL COMMENT '所属空间',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父文件夹ID，0表示根目录',
    name VARCHAR(128) NOT NULL COMMENT '文件夹名',
    created_by BIGINT NOT NULL COMMENT '创建者ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_space_parent (space_id, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件夹表';

CREATE TABLE document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    space_id BIGINT NOT NULL COMMENT '所属空间',
    folder_id BIGINT NOT NULL DEFAULT 0 COMMENT '所属文件夹，0表示根目录',
    name VARCHAR(255) NOT NULL COMMENT '文档显示名',
    file_type VARCHAR(255) DEFAULT NULL COMMENT '文件 MIME 类型',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小（字节）',
    file_path VARCHAR(512) NOT NULL COMMENT '存储路径',
    description VARCHAR(512) DEFAULT NULL COMMENT '文档描述',
    upload_by BIGINT NOT NULL COMMENT '上传者ID',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除，0未删除，1已删除',
    parse_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '解析状态: PENDING, PARSING, READY, FAILED, SKIPPED',
    chunk_count INT NOT NULL DEFAULT 0 COMMENT '生成的Chunk数量',
    parse_error VARCHAR(512) DEFAULT NULL COMMENT '解析失败异常信息',
    parsed_at DATETIME DEFAULT NULL COMMENT '完成解析时间',
    parse_version INT NOT NULL DEFAULT 0 COMMENT '解析任务乐观版本号',
    parse_started_at DATETIME DEFAULT NULL COMMENT '当前任务领取开始时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_space_folder_updated (space_id, folder_id, deleted, updated_at, id),
    KEY idx_space_deleted_updated (space_id, deleted, updated_at, id),
    KEY idx_parse_status (parse_status, deleted, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档表';

CREATE TABLE tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    space_id BIGINT NOT NULL COMMENT '所属空间',
    name VARCHAR(64) NOT NULL COMMENT '标签名',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_name (space_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签表';

CREATE TABLE document_tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    tag_id BIGINT NOT NULL COMMENT '标签ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_tag (document_id, tag_id),
    KEY idx_tag_document (tag_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档标签关联表';

-- 评论
DROP TABLE IF EXISTS comment;
CREATE TABLE comment(
    id BIGINT AUTO_INCREMENT COMMENT '评论ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    content TEXT NOT NULL COMMENT '评论内容',
    reply_to_id BIGINT DEFAULT NULL COMMENT '回复的评论ID',
    deleted TINYINT DEFAULT 0 NOT NULL COMMENT '软删除，0未删除，1已删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_document_created (document_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表';

-- 操作日志
DROP TABLE IF EXISTS operation_log;
CREATE TABLE operation_log(
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    space_id BIGINT COMMENT '空间ID',
    operation_name VARCHAR(64) NOT NULL COMMENT '操作名称',
    resource_type VARCHAR(32) COMMENT '资源类型',
    resource_id BIGINT COMMENT '资源ID',
    resource_name VARCHAR(255) COMMENT '资源名称快照 (入参 SpEL 提取)',
    method_name VARCHAR(255) NOT NULL COMMENT '方法名称',
    request_method VARCHAR(10) COMMENT '请求方法，如GET、POST、PUT、DELETE',
    request_uri VARCHAR(512) COMMENT '请求URI',
    success TINYINT NOT NULL DEFAULT 1 COMMENT '是否成功，0表示失败，1表示成功',
    error_message VARCHAR(512) COMMENT '错误信息',
    duration_ms BIGINT NOT NULL COMMENT '持续时间（毫秒）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_created (user_id, created_at) COMMENT '索引，查询某个用户的操作历史',
    KEY idx_space_created (space_id, created_at) COMMENT '索引，查询某个空间的操作历史',
    KEY idx_resource_type_id (resource_type, resource_id) COMMENT '索引，查询某个资源的操作历史'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- 先关闭停用词，再创建 ngram 索引，避免 zip 等词被过滤。
SET PERSIST innodb_ft_enable_stopword = OFF;
ALTER TABLE document ADD FULLTEXT INDEX ft_name (name) WITH PARSER ngram;
ALTER TABLE document ADD FULLTEXT INDEX ft_description (description) WITH PARSER ngram;

-- 文档正文
DROP TABLE IF EXISTS document_content;
CREATE TABLE document_content (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL COMMENT '关联文档ID',
    space_id BIGINT NOT NULL COMMENT '所属空间ID',
    chunk_index INT NOT NULL COMMENT '分块序号，从0开始递增',
    content MEDIUMTEXT NOT NULL COMMENT '正文分块文本内容',
    token_count INT NOT NULL DEFAULT 0 COMMENT '粗估 token，不代表模型实际用量',
    page_number INT DEFAULT NULL COMMENT 'PDF 页码，从 1 开始',
    char_start INT DEFAULT NULL COMMENT '块在提取文本中的起始偏移',
    char_end INT DEFAULT NULL COMMENT '块在提取文本中的结束偏移',
    image_ref VARCHAR(255) DEFAULT NULL COMMENT '原图稳定定位，不存访问URL',
    image_label VARCHAR(255) DEFAULT NULL COMMENT '图像来源位置',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_chunk (document_id, chunk_index),
    KEY idx_space_id (space_id),
    KEY idx_document_id (document_id),
    FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档正文切片表';

-- Agent 会话与执行记录
DROP TABLE IF EXISTS agent_session;
DROP TABLE IF EXISTS agent_run;
DROP TABLE IF EXISTS agent_message;
DROP TABLE IF EXISTS agent_tool_call;
DROP TABLE IF EXISTS agent_model_call;

CREATE TABLE agent_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(80) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_agent_session_owner (user_id, space_id, id)
);

CREATE TABLE agent_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    error_code VARCHAR(64),
    model_calls INT NOT NULL DEFAULT 0,
    tool_calls INT NOT NULL DEFAULT 0,
    max_model_calls INT NOT NULL,
    max_tool_calls INT NOT NULL,
    max_input_tokens INT NOT NULL,
    max_output_tokens INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3),
    finished_at DATETIME(3),
    deadline_ms BIGINT NOT NULL,
    UNIQUE KEY uk_agent_request (session_id, client_request_id),
    INDEX idx_agent_active (user_id, status),
    INDEX idx_agent_deadline (status, deadline_ms)
);

CREATE TABLE agent_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    body TEXT NOT NULL,
    dependencies JSON NOT NULL,
    sources JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_message (run_id, role),
    INDEX idx_agent_history (session_id, id)
);

CREATE TABLE agent_tool_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    sequence INT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    argument_summary VARCHAR(500) NOT NULL,
    result_summary VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    duration_ms BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_tool (run_id, sequence)
);

CREATE TABLE agent_model_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    sequence INT NOT NULL,
    estimated_input INT NOT NULL,
    max_output INT NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    usage_known BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_model (run_id, sequence)
);

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
