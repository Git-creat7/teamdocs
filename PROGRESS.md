# TeamDocs 开发进度

> 每完成一个模块就更新此文件，让下一次会话能快速接续。

## 已完成

- [x] W1: 项目骨架 + 用户模块 + JWT
- [x] W2: 空间 + 成员 + 权限（含 AOP 权限切面）
- [x] W3: 文件夹 CRUD + 文档上传/列表/重命名/移动/下载/软删除 + 回收站（列表/恢复/彻底删除）+ 标签（CRUD/打标签/摘标签/**按标签筛选文档**）
- [x] W4-1: 全文搜索（MySQL FULLTEXT + ngram，搜文档名 + 标签名）
- [x] W4-2: 评论（发表评论/回复评论/删除占位，含空间与文档归属校验）
- [x] W4-3: AOP 操作日志（成功/失败日志、URI、资源定位、耗时、失败隔离）
- [x] W5: Redis（空间详情 Cache Aside + 登录 Lua 限流 + ZSet 最近浏览）+ 单元测试 + JMeter/API 验证
- [x] W6: Docker Compose（MySQL + Redis + MinIO + Backend）+ 环境模板 + README + 容器/API 验证
- [x] 收尾加固：Spring Security 认证边界 + 统一 401 JSON + JWT 当前 Token 注销
- [x] 收尾分页：文档列表/回收站/搜索/标签文档/评论统一数据库分页
- [x] 收尾测试：移除外部 Redis 伪测试，补 JWT Filter、空间权限、评论权限、文档生命周期和分页单测
- [x] 数据卫生：删除标签和彻底删除文档时同步清理 `document_tag`，数据库操作由事务保护
- [x] 在线预览后端：新增 `GET /spaces/{spaceId}/documents/{documentId}/preview`，返回文档元数据与 `inline` MinIO 预签名 URL
- [x] 在线预览前端：接入 file-viewer，预览/下载/水印验证通过
- [x] 团队动态收尾：活动流展示修复与体验优化（文档目录定位、删除文档动态）
- [x] 部署验证：Docker Compose 与生产环境（MinIO 域名反代）全链路验证通过
- [x] 简历展示：README 重构为后端导向（技术栈 badges、架构图、启动与部署文档）
- [x] 代码质量收尾：批1 统一构造器风格与 import 规范（Lombok、通配符展开、空格）；批2 删除死代码与冗余注释、MinIO 方法重构；77 个单元测试全绿，已推送 GitHub
- [x] 部署迁移：本机 build 镜像改为 GitHub Actions 构建并发布 GHCR 镜像，服务器只 `docker compose pull`；根目录仅保留一份 `docker-compose.yaml`，删除 dev/prod 双配置和 `scripts/deploy.sh`

## 当前位置

**状态**：项目功能完结，进入维护阶段。部署迁移（CI 构建、服务器拉镜像）已提交推送，等待首次 CI 发布成功后把服务器 `.env` 的 `IMAGE_TAG` 改为该次提交 SHA。


---

## 关键代码位置

- 评论接口：`teamdocs-backend/src/main/java/asia/creat/controller/CommentController.java`
- 评论业务：`teamdocs-backend/src/main/java/asia/creat/service/impl/CommentServiceImpl.java`
- 评论查询：`teamdocs-backend/src/main/resources/asia/creat/mapper/CommentMapper.xml`
- 操作日志注解：`teamdocs-backend/src/main/java/asia/creat/anno/OperationLog.java`
- 操作目标注解：`teamdocs-backend/src/main/java/asia/creat/anno/OperationTarget.java`
- 操作日志切面：`teamdocs-backend/src/main/java/asia/creat/aspect/OperationLogAspect.java`
- 操作日志服务：`teamdocs-backend/src/main/java/asia/creat/service/impl/OperationLogServiceImpl.java`
- 操作日志实体：`teamdocs-backend/src/main/java/asia/creat/entity/OperationLogRecord.java`
- 操作日志建表 SQL：`sql/initOperationLog.sql`
- Redis 常量：`teamdocs-backend/src/main/java/asia/creat/utils/RedisConstants.java`
- Redis 缓存封装：`teamdocs-backend/src/main/java/asia/creat/utils/CacheClient.java`
- 空间详情缓存：`teamdocs-backend/src/main/java/asia/creat/service/impl/SpaceServiceImpl.java`
- 登录限流：`teamdocs-backend/src/main/java/asia/creat/service/impl/RateLimitServiceImpl.java`
- 限流 Lua：`teamdocs-backend/src/main/resources/lua/window_rate_limit.lua`
- 最近浏览业务：`teamdocs-backend/src/main/java/asia/creat/service/impl/RecentDocumentServiceImpl.java`
- 最近浏览返回对象：`teamdocs-backend/src/main/java/asia/creat/vo/RecentDocumentVO.java`
- 最近浏览权限查询：`teamdocs-backend/src/main/resources/asia/creat/mapper/DocumentMapper.xml`
- JWT 过滤器：`teamdocs-backend/src/main/java/asia/creat/filter/JwtAuthenticationFilter.java`
- Token 撤销服务：`teamdocs-backend/src/main/java/asia/creat/service/impl/TokenRevocationServiceImpl.java`
- 统一分页请求/响应：`teamdocs-backend/src/main/java/asia/creat/dto/PageQuery.java`、`teamdocs-backend/src/main/java/asia/creat/common/PageResult.java`
- W5 单元测试：`teamdocs-backend/src/test/java/asia/creat/teamdocsbackend/`
- 后端镜像：`teamdocs-backend/Dockerfile`
- Docker 构建上下文排除：`teamdocs-backend/.dockerignore`
- 全栈编排：`docker-compose.yaml`
- 环境模板：`.env.example`
- CI 与镜像发布：`.github/workflows/ci.yml`
- 部署文档：`docs/DEPLOYMENT.md`

---

## 遇到的坑（值得记录的）
### W1

7. **JWT Filter 里 filterChain.doFilter 被调用了两次**
   - 错误：try 块内放行一次，try 块外又放行一次，请求被处理两次
   - 正确：放行只在最后一次，token 验证失败时 return 提前结束
   - 记忆点：Filter 的 doFilter 在一次请求里只能调用一次，否则会报 response already committed

8. **Filter 同时用了 @Component 和 @WebFilter**
   - 错误：两种注册方式同时生效，Filter 可能被注册两次
   - 正确：Spring Boot 项目用 `@Component` 就够了，`@WebFilter` 是 Servlet 规范的传统注册方式
   - 记忆点：两套注册机制只选一种

### W2
1. **软删除手动 setDeleted(1) + updateById，绕过了 @TableLogic**
   - 错误：`space.setDeleted(1); spaceMapper.updateById(space);` —— 手动改字段 + 走 update
   - 正确：`spaceMapper.deleteById(spaceId);` —— MP 看到 `@TableLogic` 自动改写为 `UPDATE space SET deleted=1 WHERE id=?`
   - 记忆点：`@TableLogic` 的价值就是让业务代码"写删除调用、做更新效果"。一旦手写赋值，软删除细节就泄漏到业务层，注解形同虚设


3.  **校验工具方法粒度过细，导致一次接口调用 5 次 DB 查询**
   - 错误：`addMember` 里依次调用 `checkUserExist`（查 user）+ `checkMemberExist`（内部又查一次 user，再查 space_member）+ `checkRole`（查 space_member）+ 主流程再查一次 user 拿 id —— 5 次 DB 调用才插一条记录
   - 正确：把"查存在性"和"拿对象"合并成一次 `selectOne`，结果非空就同时满足两个条件；校验方法只做单一职责的事
   - 记忆点：每写一个 `checkXxx` 工具方法前，先想想它会不会和已有查询重复。**"check 类方法"很容易演变成隐藏的 N+1 查询**，因为它们看起来"只是一行"，但每行都打一次 DB

4.  **复用 LambdaQueryWrapper + clear() 重新拼条件，最后 delete 删错记录**
   - 错误：`removeMember` 里先用一个 `lqwSm` 查目标成员，然后 `lqwSm.clear()` 再加上当前登录用户的条件查操作者，最后 `spaceMemberMapper.delete(lqwSm)` —— 这时 wrapper 持有的是"操作者"的条件，删的不是目标，是自己
   - 正确：每个查询用独立的 `new LambdaQueryWrapper<>()`；删除一旦拿到主键就走 `deleteById(target.getId())`，不要再让 wrapper 参与
   - 记忆点：**wrapper 是有状态的**。`clear()` + 重新拼条件是高危操作，因为它把"查"的语义和"改/删"的语义在同一个对象上来回切换。一旦顺序乱了，删的就是错的行。规则：一个查询一个新 wrapper；按 ID 操作时优先 `xxxById`
---
### W3

1.  **`moveDocument` 目标为根目录（folderId=0）时报"目标文件夹不存在"**
   - 错误：`dto.getTargetFolderId() = 0` 不等于 null，走进了文件夹存在性校验，`selectById(0)` 返回 null，抛异常
   - 正确：加 `&& dto.getTargetFolderId() != 0` 跳过根目录校验，根目录不在 DB 里
   - 记忆点：**根目录（id=0）是虚拟概念**，不是 DB 里的真实记录。所有涉及文件夹存在性校验的地方都要排除 0

2.  **`restoreDocument` 恢复根目录文档时报"原文件夹已经被删除"**
   - 错误：`doc.getFolderId() = 0`，`selectById(0)` 返回 null，被当成"原文件夹不存在"
   - 正确：加 `doc.getFolderId() != 0` 判断，根目录跳过 DB 查询直接恢复原位
   - 记忆点：同上，id=0 永远不在 DB 里，所有恢复/移动逻辑都要特殊处理

3.  **`@TableLogic` 拦截了恢复操作的 UPDATE**
   - 错误：`doc.setDeleted(0); documentMapper.updateById(doc);` → MP 自动追加 `WHERE deleted=0`，但已删文档 `deleted=1`，WHERE 条件不匹配，UPDATE 0 行，静默失败
   - 正确：自定义 SQL `UPDATE document SET deleted=0, folder_id=? WHERE id=?`，绕过 `@TableLogic`
   - 记忆点：**`@TableLogic` 不仅在 SELECT 时自动加 `WHERE deleted=0`，UPDATE 时也会加**。恢复已删除记录只能用自定义 SQL。`@TableLogic` 是双向封锁：删（SELECT 查不到）+ 恢复（UPDATE 改不了）


### W4

3. ⭐ **`MATCH AGAINST` 必须用 `IN BOOLEAN MODE`，否则数据量小时搜啥都搜不到**
   - 错误：`MATCH(name) AGAINST(#{keyword})`（默认自然语言模式）
   - 后果：默认模式有"50% 阈值"——如果关键词在超过 50% 的行里出现，会被当成停用词，整个查询返回空。练手项目数据量小，随便搜啥都可能触发
   - 正确：`MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE)`
   - 记忆点：**默认模式假设语料库够大**（适合百万级文档的搜索引擎），数据量小用 BOOLEAN MODE。面试可以讲：BOOLEAN MODE 还支持 `+`/`-`/`*` 等操作符，更灵活

4. **`ngram_token_size=2` 导致单字搜索搜不到（ngram 固有限制，不是 bug）**
   - 现象：搜"需"搜不到"需求文档"，搜"需求"能搜到
   - 原因：ngram 解析器按 2 字符滑窗切词，"需求文档"切成"需求""求文""文档"，单字切不出 token，索引里没有单字
   - 记忆点：**ngram 牺牲了单字搜索能力换取中文支持**。这是设计取舍，不是实现问题。要支持单字搜，得换 IK 分词器或调大 `ngram_token_size`（但调大会让索引膨胀）

5. **搜索 SQL 的 `LEFT JOIN` + `DISTINCT` 组合**
   - 要 OR 标签名（文档名 OR 标签名命中都算搜到），必须 `LEFT JOIN document_tag` + `LEFT JOIN tag`。如果用 `JOIN`（内连接），没打标签的文档直接被滤掉，搜不到
   - 一个文档多标签命中会产生多行，必须 `SELECT DISTINCT` 去重，否则同一文档返回多次
   - 记忆点：**`OR` 跨表条件必须配 `LEFT JOIN`，多对多 join 必须配 `DISTINCT`**。两个一起记

7.  **回复评论必须同时校验存在、文档归属和删除状态**
   - 错误：只校验 `replyToId` 对应的评论存在，攻击者可以把其他文档的评论 ID 作为回复目标
   - 正确：被回复评论必须存在、属于当前文档且未删除
   - 记忆点：**自指关联同样存在越权风险**。任何由客户端提交的关联 ID 都要校验它与当前资源处于同一业务边界

8.  **操作日志失败不能反向阻断主业务**
   - 风险：切面保存日志时数据库异常，如果异常继续向外抛，原本成功的上传、评论或成员操作会被日志模块拖垮
   - 正确：日志保存由切面兜底捕获；日志 Service 使用 `REQUIRES_NEW` 独立事务，业务失败时也能提交失败日志
   - 记忆点：**审计日志是旁路能力，必须失败隔离**。记录失败不能改变被记录业务原本的成功或失败结果



10.  **标签写操作也要校验标签归属空间**
   - 错误：给文档添加标签、重命名标签时只按 `tagId` 查询，没有比较 `tag.spaceId` 与当前空间
   - 正确：抽出 `checkTag(spaceId, tagId)`，在删除、重命名、添加、移除和按标签查询中复用
   - 记忆点：**读接口修过的越权问题，写接口也要系统性排查**。不能只修一个入口，所有接收同类资源 ID 的方法都要统一校验

### 前端联调期 (后端侧发现)

1.  **ngram 全文索引搜不到 "zip"——InnoDB 默认停用词的连带杀伤**
   - 现象：文档名含 `zip` 却搜不到；`md`、`txt`、中文都正常
   - 原因：ngram 把 "zip" 切成 "zi"/"ip"，而 InnoDB 默认停用词表含单词 "i"，**ngram 的规则是 token 里"包含"停用词即整体丢弃**，所以带 i 的英文 bigram 全军覆没 (zi、ip、in、is…)
   - 修复：`SET PERSIST innodb_ft_enable_stopword = OFF` 后重建两个 FULLTEXT 索引；`docker-compose.yaml` 的 mysql command 加 `--innodb-ft-enable-stopword=OFF`；`sql/fulltext_index.sql` 已同步
   - 记忆点：**ngram + 默认停用词 = 部分英文短词永远进不了索引**。建 ngram 索引前先关停用词，已建过的必须重建才回填

### W5

1.  **缓存不是越多越好，只缓存高频读取且失效边界清楚的数据**
   - 空间详情适合 Cache Aside；文档列表、评论和搜索结果更新入口多，当前阶段强行缓存会显著增加一致性复杂度
   - `/user/info` 直接返回 JWT 解析出的 `LoginUser`，本身不查数据库，再加 Redis 只会增加一次网络访问
   - 记忆点：**Redis 的价值来自解决具体问题，不来自覆盖了多少个模块**

2.  **空值缓存必须使用明确哨兵，不能用空字符串**
   - 不存在的空间缓存为 `"NULL"`，TTL 60 秒；查询命中哨兵时直接返回“空间不存在”
   - 空字符串会与“未命中/空白值”判断混在一起，容易再次访问数据库
   - 记忆点：**空值缓存要能和普通值、缓存未命中明确区分，并使用短 TTL**

3. **正常缓存 TTL 加随机抖动，降低同一时刻集中失效风险**
   - 空间详情基础 TTL 30 分钟，再增加 0～300 秒随机值
   - 更新、删除空间时先更新数据库，再删除对应缓存 Key
   - 记忆点：缓存一致性采用 Cache Aside：**读时回填，写时更新数据库后删缓存**

4.  **限流的计数与首次过期设置必须保持原子性**
   - 使用 Redis + Lua 完成 `INCR` 与首次 `EXPIRE`，避免并发下出现只有计数没有 TTL 的永久 Key
   - 登录按 IP 固定窗口限制为 60 秒最多 10 次；Redis 故障时降级放行，避免缓存故障拖垮登录主业务
   - 记忆点：**需要多条 Redis 命令共同保证一个语义时，用 Lua 合成一次原子执行**

5.  **最近浏览记录必须落在成功获取下载 URL 之后**
   - 权限、文档查询或 MinIO 生成预签名 URL 失败时不能记录浏览
   - `recordRecentDocument` 放在独立 Spring Bean 中并使用 `@Async`；启动类通过 `@EnableAsync` 开启代理
   - 记忆点：`@Async` 自调用不会生效，必须经过 Spring 代理；旁路记录失败不能影响下载主流程


7. **Redis 只保存最近文档 ID，卡片元数据仍从 MySQL 查询**
   - `ZREVRANGE WITHSCORES` 先取有序 ID 与浏览时间，再一次 JOIN `document + space + space_member` 查询当前仍可访问的文档
   - 自定义 XML SQL 必须显式过滤文档/空间软删除；多参数 Mapper 使用 `@Param`；空 ID 列表不能生成 `IN ()`
   - SQL 的 `IN` 不保证结果顺序，Service 需要按 Redis ID 顺序重新组装，并用 score 填充 `lastViewedAt`

8. **失效最近记录采用查询时惰性清理，不扫描所有用户 Key**
   - 文档删除或成员失去空间权限后，JOIN 会过滤对应文档；Service 计算差集后批量 `ZREM`
   - 删除文档时无法高效获知哪些用户浏览过它，禁止扫描 `teamdocs:user:recent:*`
   - 记忆点：**有界的冗余索引可以在读取时修复，避免高成本的全局反向查找**

---

## W5 Redis 实施与验证结果

### 空间详情缓存

- Cache Aside 查询通过：命中直接返回，未命中查询 MySQL 后回填
- 不存在空间使用 `"NULL"` 哨兵缓存，TTL 60 秒，防止缓存穿透
- 正常缓存 TTL 为 1800～2100 秒，多个 Key 实测具有随机抖动
- 更新、删除空间后删除缓存；Redis 异常时回退 MySQL，不阻断业务

### 登录限流

- Redis + Lua 固定窗口：同一 IP 每 60 秒最多 10 次登录
- 连续 11 次请求实测：前 10 次允许，第 11 次拒绝
- JMeter 20 并发：10 次允许、10 次拒绝、HTTP 错误 0
- JMeter 结果：平均 125.6ms，P95 263ms，最大 263ms

### 最近浏览

- `GET /user/recent-documents` 返回跨空间最近文档卡片，数据库 JOIN 负责当前权限和软删除过滤
- 真实下载成功后异步写入 ZSet，接口返回文档名、空间名、更新时间和 `lastViewedAt`
- 端到端实测：ZSet 成员数 1，TTL 2,591,965 秒，符合约 30 天
- 人工加入无效文档 ID 后，接口正确过滤并通过 `ZREM` 将成员数从 3 清理为 2
- MinIO 服务未启动时，流程在生成预签名 URL 处失败，不产生错误的最近浏览记录；服务启动后完整链路通过

### 测试

- `CacheClientTest`：5 个用例通过
- `RateLimitServiceImplTest`：5 个用例通过
- `RecentDocumentServiceImplTest`：4 个用例通过，覆盖写入裁剪、空集合、顺序恢复/时间填充、失效成员清理
- 最近浏览最新 Maven 结果：4 个测试，0 失败，0 错误，`BUILD SUCCESS`

### 范围决策

- 当前阶段不做逻辑过期、Redisson、缓存预热和多级缓存，避免为当前数据规模增加无必要复杂度
- 明确放弃 Elasticsearch；全文搜索保留 MySQL FULLTEXT，W6 清理仍残留的 ES/Kibana 依赖与配置
- 当前不引入微服务、Yjs、RAG/Agent，优先完成部署、文档、回归和简历表达

---

## W2 AOP 权限切面改造过程

### 改造动机

成员管理一通写下来，发现 `SpaceServiceImpl` 里几乎每个方法开头都长一样：

```java
checkSpace(spaceId);
checkRole(spaceId, loginUser, SpaceRole.OWNER);
// 真正的业务逻辑...
```

`deleteSpace`、`updateSpace`、`addMember`、`updateMemberRole` 四个方法重复这套样板。问题：

1. **样板代码淹没业务**：方法体一半是校验、一半是业务，读代码要先跳过前两行
2. **权限规则散落在方法体里**：想看"谁能删空间"必须翻进方法找 `checkRole` 调用
3. **改一次要改 N 处**：如果以后 OWNER 的判断逻辑变了，要逐个方法去改

目标：把权限校验从方法体抽到注解上，让 Service 方法只剩业务逻辑。

### 设计决策

#### 决策一：用 Spring AOP，不用 BeanPostProcessor 之类的低级方案

Spring AOP 是高频面试点，且现成的 `@Around` 注解就能覆盖所有需求，不需要自己写代理。

#### 决策二：自定义注解 `@RequireSpaceRole`，而不是直接用切点表达式匹配方法名

切点表达式（如 `execution(* asia.creat.service.impl.SpaceServiceImpl.delete*(..))`）匹配的是**方法名模式**，强耦合命名规范。一旦方法改名或新增方法，切点要跟着改。注解方式让权限规则**显式地标在方法上**，谁要权限校验、要哪些角色，看一眼方法签名就明白。

#### 决策三：参数定位用 `@SpaceId` 注解 + 类型匹配 LoginUser

切面拿到的是 `Object[] args`，它不知道哪个参数是 `spaceId`、哪个是 `loginUser`。两种方案：

- **方案 A 按位置约定**：第一个参数永远是 spaceId，最后一个永远是 loginUser
- **方案 B 按注解 + 类型**：`@SpaceId` 标注的 Long 是 spaceId，类型为 LoginUser 的就是 loginUser

选 B。原因：`updateMemberRole(Long spaceId, Long targetUserId, ...)` 有两个 Long，按位置取很容易拿错。注解让"哪个是 spaceId"变成显式声明，不依赖位置约定。`LoginUser` 类型全局唯一，按类型匹配就够了，不需要再加 `@LoginUser` 注解。

### 过程中踩的坑



**3. 方法上加了 `@RequireSpaceRole` 但参数上忘加 `@SpaceId`**

切面遍历参数找不到 `@SpaceId` 标记的 Long，抛 `IllegalStateException`。**记忆点**：注解化的方法必须**成对**贴注解：方法上一个、spaceId 参数上一个。可以考虑日后写单测覆盖每个注解化方法。

**4. 注解属性名用 `allowedRoles` 而不是 `value`**

不是 bug，但使用时必须写全：`@RequireSpaceRole(allowedRoles = SpaceRole.OWNER)`，不能简写。**记忆点**：单属性注解优先用 `value()`，让调用处更简洁；多属性才用具名属性。

**5. `@Aspect` 必须配合 `@Component` 才能生效**

光 `@Aspect` 只是告诉 AspectJ 这是切面，但 Spring AOP 框架要从**容器里**拿到这个 Bean 才能让它工作。两个注解一起贴，缺一不可。**记忆点**：`@Aspect` = 切面身份，`@Component` = 被 Spring 管理。Spring AOP 是基于代理的，必须是 Bean 才能被代理。

### 后续可优化点

- 切面里查空间 + 查角色是两次 DB 查询，可以合并为一次 join 或用一次 `selectOne` 同时校验
- 目前 `@RequireSpaceRole` 和业务异常文案是硬编码的，多语言场景需要改成 i18n key
- 可以加个 `@Pointcut` 把 `@annotation(...)` 表达式抽出来命名，多个 advice 复用

---

## 收尾审查决策

- Redis 已覆盖 Cache Aside、Lua 登录限流、ZSet 最近浏览和 JWT 撤销名单，不再为了数量给所有查询强行加缓存。
- 本阶段只实现注销当前 Token；Refresh Token 需要双 Token 返回契约、轮换和重放检测，留到有前端长会话需求时再做。
- 文档、搜索、回收站、标签文档和评论属于潜在大列表，已统一分页；空间、成员、单层文件夹和标签列表当前数据量有限，暂不扩大改动面。
- 单元测试已覆盖认证、权限、文档生命周期、Redis 故障隔离和分页模型；MySQL FULLTEXT、MinIO 与完整 Controller 链路仍由最终 API 回归验证。

---

## 待澄清的问题

（开发中填写）
