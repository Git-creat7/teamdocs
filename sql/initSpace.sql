DROP TABLE IF EXISTS space;

CREATE TABLE space(
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '空间名称',
    description VARCHAR(255) DEFAULT NULL COMMENT '空间描述',
    owner_id BIGINT NOT NULL COMMENT '空间创建者ID',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除，0表示未删除，1表示已删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
)ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '空间表';


DROP TABLE IF EXISTS space_member;

CREATE TABLE space_member(
    id BIGINT NOT NULL AUTO_INCREMENT,
    space_id BIGINT NOT NULL COMMENT '空间ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(16) NOT NULL COMMENT '成员角色，如owner、admin、member',
    joined_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_user (space_id, user_id),
    KEY idx_user(user_id)
)ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '空间成员表';

