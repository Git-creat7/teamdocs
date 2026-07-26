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

    KEY idx_user_created (user_id,created_at) COMMENT '索引，查询某个用户的操作历史',
    KEY idx_space_created (space_id,created_at) COMMENT '索引，查询某个空间的操作历史',
    KEY idx_resource_type_id (resource_type,resource_id) COMMENT '索引，查询某个资源的操作历史'
)ENGINE = InnoDB DEFAULT  CHARSET = utf8mb4 COMMENT = '操作日志表';
