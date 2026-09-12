# TeamDocs 问题与修复记录

## 当前状态

功能已完结，进入维护阶段。部署方式为 GitHub Actions 构建并发布 GHCR 镜像，服务器只执行 `docker compose pull && up -d`。后端 16 个测试类、83 个单元测试全绿。

## Bug 与修复

### 认证与权限

1. **一次请求被处理两次，报 `response already committed`**
   - 原因：JWT Filter 在 try 内外各调了一次 `filterChain.doFilter`
   - 修复：只在末尾放行一次，Token 校验失败直接 `return`
2. **Filter 可能被注册两次**
   - 原因：同时使用 `@Component` 和 `@WebFilter`
   - 修复：只保留 `@Component`
3. **回复评论可指向其他文档的评论**
   - 原因：只校验了被回复评论是否存在
   - 修复：校验被���复评论属于当前文档且未删除
4. **标签写操作可跨空间操作**
   - 原因：只按 `tagId` 查询，未比较 `tag.spaceId`
   - 修复：抽出 `checkTag(spaceId, tagId)`，删除、重命名、打标、摘标统一复用
5. **权限切面报 `IllegalStateException`**
   - 原因：方法加了 `@RequireSpaceRole` 但参数漏了 `@SpaceId`
   - 修复：两个注解成对使用，切面按注解定位 spaceId、按类���定位 LoginUser

### MyBatis-Plus 与数据

1. **软删除绕过了 `@TableLogic`**
   - 原因：手动 `setDeleted(1)` 再 `updateById`
   - 修复：改用 `deleteById`，由注解改写为逻辑删除
2. **恢复回收站文档时 UPDATE 0 行、静默失败**
   - 原因：`@TableLogic` 对 UPDATE 也追加 `deleted=0`，已删记录匹配不到
   - 修复：自定义 SQL `UPDATE document SET deleted=0 ... WHERE id=?`
3. **移动或恢复到根目录报"文件夹不存在"**
   - 原因：根目录 `folderId=0` 不是数据库记录，`selectById(0)` 为空
   - 修复：所有文件夹存在性校验排除 0
4. **`removeMember` 删错记录**
   - 原因：复用 `LambdaQueryWrapper` 并 `clear()` 后重拼条件，最终 delete 用的是操作者条件
   - 修复：一个查询一个 wrapper，拿到主键后走 `deleteById`
5. **`addMember` 一次调用打 5 次数据库**
   - 原因：多个 `checkXxx` 各自查库且互相重复
   - 修复：合并为一次 `selectOne`，存在性校验与取对象一起完成

### 全文搜索

1. **搜什么都搜不到**
   - 原因：`MATCH AGAINST` 默认自然语言模式有 50% 阈值，小数据量下关键词被当停用词
   - 修复：改为 `IN BOOLEAN MODE`
2. **文档名含 `zip` 却搜不到**
   - 原因：ngram 切成 `zi`、`ip`，InnoDB 默认停用词含 `i`，含停用词的 token 整体丢弃
   - 修复：`innodb_ft_enable_stopword=OFF` 后重建索引，Compose 里 MySQL 启动参数同步
3. **按标签名命中时漏文档或重复**
   - 原因：内连接过滤掉无标签文档，多标签命中产生多行
   - 修复：`LEFT JOIN` 加 `SELECT DISTINCT`
4. **单字搜不到**
   - 原因：`ngram_token_size=2` 固有限制
   - 修复：接受取舍，不改分词器

### Redis 与旁路能力

1. **限流 Key 可能永不过期**
   - 原因：`INCR` 与首次 `EXPIRE` 不是原子操作
   - 修复：Lua 脚本合并执行，Redis 故障时降级放行
2. **空值缓存与未命中混淆**
   - 原因：用空字符串表示"不存在"
   - 修复：使用 `"NULL"` 哨兵，TTL 60 秒
3. **缓存集中失效**
   - 原因：固定 TTL
   - 修复：30 分钟基础 TTL 加 0～300 秒随机抖动
4. **`@Async` 最近浏览记录不异步**
   - 原因：Service 自调用不经过 Spring 代理
   - 修复：放到独立 Bean，启动类 `@EnableAsync`
5. **最近浏览顺序错乱、已删文档残留**
   - 原因：SQL `IN` 不保证顺序，删除文档时无法反查浏览者
   - 修复：按 Redis ZSet 顺序重组，查询时惰性 `ZREM` 失效成员
6. **操作日志失败拖垮主业务**
   - 原因：切面异常向外抛
   - 修复：切面兜底捕获，日志 Service 用 `REQUIRES_NEW` 独立事务
