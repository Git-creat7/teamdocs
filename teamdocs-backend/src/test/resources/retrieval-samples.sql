INSERT INTO space (id, name, owner_id, deleted) VALUES (1, '检索', 7, 0), (2, '别的空间', 7, 0), (3, '已删空间', 7, 1);

INSERT INTO document (id, space_id, name, file_path, upload_by, deleted, parse_status, parse_version) VALUES
                    (10, 1, '上线手册.md', 'k/10', 7, 0, 'READY', 3),
                    (11, 2, '别的空间.md', 'k/11', 7, 0, 'READY', 1),
                    (12, 1, '已删除.md', 'k/12', 7, 1, 'READY', 1),
                    (13, 1, '重新解析中.md', 'k/13', 7, 0, 'PENDING', 2),
                    (14, 3, '空间已删.md', 'k/14', 7, 0, 'READY', 1),
                    (15, 1, '会议室.md', 'k/15', 7, 0, 'READY', 1),
                    (16, 1, '部署说明.md', 'k/16', 7, 0, 'READY', 1);

INSERT INTO document_content (document_id, space_id, chunk_index, content, page_number, char_start, char_end) VALUES
                    (10, 1, 0, '上线检查清单：先备份数据库，再执行迁移脚本。', 2, 0, 22),
                    (10, 1, 1, '回滚步骤：从备份恢复后重启服务。', 2, 22, 38),
                    (11, 2, 0, '上线检查清单：先备份数据库。', NULL, 0, 14),
                    (12, 1, 0, '上线检查清单：先备份数据库。', NULL, 0, 14),
                    (13, 1, 0, '上线检查清单：先备份数据库。', NULL, 0, 14),
                    (14, 3, 0, '上线检查清单：先备份数据库。', NULL, 0, 14),
                    (15, 1, 0, '会议室预约不要写入上线步骤。', NULL, 0, 14),
                    (16, 1, 0, '部署前确认 API 网关限流，zip 压缩包不超过 10MB。', NULL, 0, 31);
