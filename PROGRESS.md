# TeamDocs 问题与修复记录

## 当前状态

功能已完结，进入维护阶段。部署方式为 GitHub Actions 构建并发布 GHCR 镜像，服务器只执行 `docker compose pull && up -d`。后端 16 个测试类、83 个单元测试全绿。

## Bug 与修复

### 认证与权限

| 问题 | 原因 | 修复 |
|---|---|---|
| 一次请求被处理两次，报 `response already committed` | JWT Filter 在 try 内外各调了一次 `filterChain.doFilter` | 只在末尾放行一次，Token 校验失败直接 `return` |
| Filter 可能被注册两次 | 同时使用 `@Component` 和 `@WebFilter` | 只保留 `@Component` |
| 回复评论可指向其他文档的评论 | 只校验了被回复评论是否存在 | 校验被回复评论属于当前文档且未删除 |
| 标签写操作可跨空间操作 | 只按 `tagId` 查询，未比较 `tag.spaceId` | 抽出 `checkTag(spaceId, tagId)`，删除/重命名/打标/摘标统一复用 |
| 权限切面报 `IllegalStateException` | 方法加了 `@RequireSpaceRole` 但参数漏了 `@SpaceId` | 两个注解成对使用，切面按注解定位 spaceId、按类型定位 LoginUser |

### MyBatis-Plus 与数据

| 问题 | 原因 | 修复 |
|---|---|---|
| 软删除绕过了 `@TableLogic` | 手动 `setDeleted(1)` + `updateById` | 改用 `deleteById`，由注解改写为逻辑删除 |
| 恢复回收站文档时 UPDATE 0 行、静默失败 | `@TableLogic` 对 UPDATE 也追加 `deleted=0`，已删记录匹配不到 | 自定义 SQL `UPDATE document SET deleted=0 ... WHERE id=?` |
| 移动/恢复到根目录报"文件夹不存在" | 根目录 `folderId=0` 不是数据库记录，`selectById(0)` 为空 | 所有文件夹存在性校验排除 0 |
| `removeMember` 删错记录 | 复用 `LambdaQueryWrapper` 并 `clear()` 后重拼条件，最终 delete 用的是操作者条件 | 一个查询一个 wrapper；拿到主键后走 `deleteById` |
| `addMember` 一次调用打 5 次数据库 | 多个 `checkXxx` 各自查库且互相重复 | 合并为一次 `selectOne`，存在性与取对象一起完成 |

### 全文搜索

| 问题 | 原因 | 修复 |
|---|---|---|
| 搜什么都搜不到 | `MATCH AGAINST` 默认自然语言模式有 50% 阈值，小数据量下关键词被当停用词 | 改为 `IN BOOLEAN MODE` |
| 文档名含 `zip` 却搜不到 | ngram 切成 `zi`/`ip`，InnoDB 默认停用词含 `i`，含停用词的 token 整体丢弃 | `innodb_ft_enable_stopword=OFF` 后重建索引；Compose 里 MySQL 启动参数同步 |
| 按标签名命中时漏文档或重复 | 内连接过滤掉无标签文档；多标签命中产生多行 | `LEFT JOIN` + `SELECT DISTINCT` |
| 单字搜不到 | `ngram_token_size=2` 固有限制 | 接受取舍，不改分词器 |

### Redis 与旁路能力

| 问题 | 原因 | 修复 |
|---|---|---|
| 限流 Key 可能永不过期 | `INCR` 与首次 `EXPIRE` 不是原子操作 | Lua 脚本合并执行；Redis 故障时降级放行 |
| 空值缓存与未命中混淆 | 用空字符串表示"不存在" | 使用 `"NULL"` 哨兵，TTL 60 秒 |
| 缓存集中失效 | 固定 TTL | 30 分钟基础 TTL + 0～300 秒随机抖动 |
| `@Async` 最近浏览记录不异步 | Service 自调用不经过 Spring 代理 | 放到独立 Bean，启动类 `@EnableAsync` |
| 最近浏览顺序错乱、已删文档残留 | SQL `IN` 不保证顺序；删除文档时无法反查浏览者 | 按 Redis ZSet 顺序重组；查询时惰性 `ZREM` 失效成员 |
| 操作日志失败拖垮主业务 | 切面异常向外抛 | 切面兜底捕获；日志 Service 用 `REQUIRES_NEW` 独立事务 |

### 前端

| 问题 | 原因 | 修复 |
|---|---|---|
| 快速切换空间后旧空间的数据覆盖新空间 | 异步请求无序返回，响应未校验是否仍属当前空间 | 文档详情、文件夹内容、文件夹导航加请求序号与 `workspaceRevision` 校验，过期响应直接丢弃 |

### 构建与部署

| 问题 | 原因 | 修复 |
|---|---|---|
| Docker 构建时 Maven/npm 下载偶发失败 | 网络抖动，单次失败即中断构建 | Dockerfile 内重试 3 次；`--mount=type=cache` 保留依赖缓存 |
| 服务器上构建镜像慢且不可复现 | 本机 build + 两套 Compose | CI 发布带提交 SHA 的镜像，服务器只 pull；根目录只保留一份 `docker-compose.yaml` |
