# TeamDocs 开发进度

> 每完成一个模块就更新此文件，让下一次会话能快速接续。

---

## 当前位置

**周次**：W3 → W4
**模块**：全文搜索 + 评论 + AOP 日志
**状态**：W3 已完成，准备开始 W4

---

## 已完成

- [x] W1: 项目骨架 + 用户模块 + JWT
- [x] W2: 空间 + 成员 + 权限（含 AOP 权限切面）
- [x] W3: 文件夹 CRUD + 文档上传/列表/重命名/移动/下载/软删除 + 回收站（列表/恢复/彻底删除）+ 标签（CRUD/打标签/摘标签）

---

## 进行中

- [ ] W4: 全文搜索 + 评论 + AOP 日志

---

## 待办

- [ ] W4: 全文搜索 + 评论 + AOP 日志
- [ ] W5: Redis 缓存 + 限流 + 单测
- [ ] W6: Docker Compose + 部署文档

---

## 关键代码位置

（开发中填写，例如：）
- 用户模块：`teamdocs-backend/src/main/java/com/teamdocs/user/`
- JWT 配置：`teamdocs-backend/src/main/java/com/teamdocs/config/JwtConfig.java`

---

## 遇到的坑（值得记录的）
### W1
1. **BusinessException 继承了 Throwable 而不是 RuntimeException**
   - 错误：`extends Throwable` → 变成受检异常，所有调用方法都要声明 `throws`
   - 正确：`extends RuntimeException` → 非受检异常，随抛随捕，全局兜底
   - 记忆点：自定义业务异常永远继承 `RuntimeException`

2. **GlobalExceptionHandler 里把 @ExceptionHandler 写进了注释**
   - 导致 `handleDefaultException` 方法是死代码，Spring 永远不会调用它
   - 教训：注解必须在方法签名上方，不是注释里

3. **User 实体的 DateTime 导错包**
   - 错误：导入了 `net.sf.jsqlparser.expression.DateTimeLiteralExpression.DateTime`（SQL 解析器内部类）
   - 正确：`java.time.LocalDateTime`（Java 8+ 标准时间类型，MP 原生支持）
   - 记忆点：IDEA 自动补全不一定对，看清包路径再确认

4. **User 实体 id 字段缺 @TableId(type = IdType.AUTO)**
   - 没有这个注解，MyBatis Plus 默认用雪花算法生成 ID，不走数据库自增
   - 记忆点：只要表用了 AUTO_INCREMENT，实体必须显式声明 `IdType.AUTO`

5. **JDBC URL 的 characterEncoding=utf8mb4 导致连接失败**
   - 错误：`characterEncoding=utf8mb4`（这是 MySQL 服务端的字符集名）
   - 正确：`characterEncoding=UTF-8`（JDBC 要求用 Java 字符集名）
   - 记忆点：MySQL 字符集名 ≠ Java 字符集名，JDBC URL 里用 Java 的

6. **JJWT 版本与 API 不匹配**
   - 错误：pom 用 0.11.5 但代码写了 0.12.x 的 API（`.claims()` / `.verifyWith()` / `Jwts.parser()`）
   - 正确：版本和 API 必须对应，0.11 用 `setClaims/parserBuilder/setSigningKey`，0.12 用 `claims/parser/verifyWith`
   - 记忆点：引入第三方库时先确认版本号，再查对应版本的文档/API

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

2. **角色多值匹配用 OR 拼接而不是 IN**
   - 错误：`.and(w -> { for r : roles { w.or().eq(role, r); } })` —— 循环拼 OR，代码长又难读
   - 正确：`.in(SpaceMember::getRole, Arrays.asList(rolesToCheck))` —— 直接用 IN
   - 记忆点：等值多选场景一律用 `IN`。MySQL 优化器对 IN 的处理比 OR 更稳（OR 多了优化器有时退化成全表扫，IN 总是走索引）。代码也短

3. ⭐ **校验工具方法粒度过细，导致一次接口调用 5 次 DB 查询**
   - 错误：`addMember` 里依次调用 `checkUserExist`（查 user）+ `checkMemberExist`（内部又查一次 user，再查 space_member）+ `checkRole`（查 space_member）+ 主流程再查一次 user 拿 id —— 5 次 DB 调用才插一条记录
   - 正确：把"查存在性"和"拿对象"合并成一次 `selectOne`，结果非空就同时满足两个条件；校验方法只做单一职责的事
   - 记忆点：每写一个 `checkXxx` 工具方法前，先想想它会不会和已有查询重复。**"check 类方法"很容易演变成隐藏的 N+1 查询**，因为它们看起来"只是一行"，但每行都打一次 DB

4. ⭐ **复用 LambdaQueryWrapper + clear() 重新拼条件，最后 delete 删错记录**
   - 错误：`removeMember` 里先用一个 `lqwSm` 查目标成员，然后 `lqwSm.clear()` 再加上当前登录用户的条件查操作者，最后 `spaceMemberMapper.delete(lqwSm)` —— 这时 wrapper 持有的是"操作者"的条件，删的不是目标，是自己
   - 正确：每个查询用独立的 `new LambdaQueryWrapper<>()`；删除一旦拿到主键就走 `deleteById(target.getId())`，不要再让 wrapper 参与
   - 记忆点：**wrapper 是有状态的**。`clear()` + 重新拼条件是高危操作，因为它把"查"的语义和"改/删"的语义在同一个对象上来回切换。一旦顺序乱了，删的就是错的行。规则：一个查询一个新 wrapper；按 ID 操作时优先 `xxxById`
---
### W3

1. ⭐ **`moveDocument` 目标为根目录（folderId=0）时报"目标文件夹不存在"**
   - 错误：`dto.getTargetFolderId() = 0` 不等于 null，走进了文件夹存在性校验，`selectById(0)` 返回 null，抛异常
   - 正确：加 `&& dto.getTargetFolderId() != 0` 跳过根目录校验，根目录不在 DB 里
   - 记忆点：**根目录（id=0）是虚拟概念**，不是 DB 里的真实记录。所有涉及文件夹存在性校验的地方都要排除 0

2. ⭐ **`restoreDocument` 恢复根目录文档时报"原文件夹已经被删除"**
   - 错误：`doc.getFolderId() = 0`，`selectById(0)` 返回 null，被当成"原文件夹不存在"
   - 正确：加 `doc.getFolderId() != 0` 判断，根目录跳过 DB 查询直接恢复原位
   - 记忆点：同上，id=0 永远不在 DB 里，所有恢复/移动逻辑都要特殊处理

3. ⭐ **`@TableLogic` 拦截了恢复操作的 UPDATE**
   - 错误：`doc.setDeleted(0); documentMapper.updateById(doc);` → MP 自动追加 `WHERE deleted=0`，但已删文档 `deleted=1`，WHERE 条件不匹配，UPDATE 0 行，静默失败
   - 正确：自定义 SQL `UPDATE document SET deleted=0, folder_id=? WHERE id=?`，绕过 `@TableLogic`
   - 记忆点：**`@TableLogic` 不仅在 SELECT 时自动加 `WHERE deleted=0`，UPDATE 时也会加**。恢复已删除记录只能用自定义 SQL。`@TableLogic` 是双向封锁：删（SELECT 查不到）+ 恢复（UPDATE 改不了）

4. **`DocumentMapper.xml` 中 MyBatis 参数占位符缺 `#`**
   - 错误：`folder_id = {folderId}` → MyBatis 不解析 `{}`，当普通字符串发给 MySQL，SQL 语法错误但异常被吞
   - 正确：`folder_id = #{folderId}`
   - 记忆点：**MyBatis XML 中参数占位符永远用 `#{}`**，缺 `#` 不报编译错，运行时才暴露

5. **自定义 SQL 参数名与 Java 方法参数名大小写不一致**
   - 错误：Java 方法参数 `Long FolderId`（大写 F），XML 写 `#{folderId}`（小写 f），MyBatis 报 `Parameter 'folderId' not found. Available parameters are [documentId, FolderId, param1, param2]`
   - 正确：`#{FolderId}` 或用 `@Param("folderId")`
   - 记忆点：**不带 `@Param` 时，MyBatis 按编译后参数名匹配**。IDEA 默认 `-parameters` 编译，参数名即原样。大小写必须一致

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

### 实施步骤

#### Step 1：加 AOP 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

Spring Boot 默认不带 AOP starter。引入后会自动配置 AspectJ Auto Proxy（基于 CGLIB 给 Bean 生成代理对象，方法调用先经过代理，代理把切面织入进去）。

#### Step 2：写两个注解

```java
// 标在方法上，声明该方法需要哪些角色
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireSpaceRole {
    SpaceRole[] value() default {SpaceRole.OWNER, SpaceRole.ADMIN};
}

// 标在参数上，告诉切面"这个 Long 是 spaceId"
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpaceId {
}
```

关键理解：
- `@Retention(RUNTIME)` 必须有，否则编译后注解会被擦除，反射读不到
- `@Target` 限制注解能贴在哪种语法位置上，错了编译都过不了
- `value()` 是注解的特殊属性名，使用时可以省略：`@RequireSpaceRole(SpaceRole.OWNER)` 等价于 `@RequireSpaceRole(value = SpaceRole.OWNER)`

#### Step 3：写切面 `SpaceRoleAspect`

```java
@Slf4j
@Component   // 让 Spring 扫描成 Bean
@Aspect      // 告诉 AOP 框架这是切面类
public class SpaceRoleAspect {

    @Autowired private SpaceMemberMapper spaceMemberMapper;
    @Autowired private SpaceMapper spaceMapper;

    @Around("@annotation(asia.creat.anno.RequireSpaceRole)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        // 1. 反射拿到方法上的 @RequireSpaceRole 注解
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        RequireSpaceRole ano = method.getAnnotation(RequireSpaceRole.class);
        SpaceRole[] roles = anno.value();
        if (roles == null || roles.length == 0) {
            roles = new SpaceRole[]{SpaceRole.OWNER, SpaceRole.ADMIN};
        }

        // 2. 遍历参数找 spaceId 和 loginUser
        Object[] args = pjp.getArgs();
        Annotation[][] paramAnnos = method.getParameterAnnotations();
        Long spaceId = null;
        LoginUser loginUser = null;
        for (int i = 0; i < args.length; i++) {
            for (Annotation a : paramAnnos[i]) {
                if (a instanceof asia.creat.anno.SpaceId) {
                    spaceId = (Long) args[i];
                }
            }
            if (args[i] instanceof LoginUser) {
                loginUser = (LoginUser) args[i];
            }
        }
        if (spaceId == null || loginUser == null) {
            throw new IllegalStateException("未找到空间ID或登录用户");
        }

        // 3. 校验空间存在 + 用户在该空间的角色 ∈ roles
        if (spaceMapper.selectById(spaceId) == null) {
            throw new BusinessException("空间不存在");
        }
        long count = spaceMemberMapper.selectCount(
            new LambdaQueryWrapper<SpaceMember>()
                .eq(SpaceMember::getSpaceId, spaceId)
                .eq(SpaceMember::getUserId, loginUser.getUserId())
                .in(SpaceMember::getRole, Arrays.asList(roles))
        );
        if (count == 0) {
            throw new BusinessException("您没有权限操作该空间");
        }

        // 4. 放行
        return pjp.proceed();
    }
}
```

关键 API：
- `ProceedingJoinPoint` 是 `@Around` 专属的连接点对象，比普通 `JoinPoint` 多一个 `proceed()` 方法用来放行目标方法
- `MethodSignature.getMethod()` 拿到 `java.lang.reflect.Method`，可以反射读注解
- `getParameterAnnotations()` 返回二维数组 `[参数索引][该参数上的注解]`，所以判断"第 i 个参数有没有 `@SpaceId`"要内层再循环一次
- `pjp.proceed()` 是放行开关，不调用就等于拦截了请求

异常类型选择：
- `IllegalStateException`：程序员把注解用错（带了 `@RequireSpaceRole` 却忘了 `@SpaceId`），用户怎么操作都触发不了，让它直接 500，开发者立刻能发现
- `BusinessException`：用户操作触发的业务错误（无权限、空间不存在），交给 `GlobalExceptionHandler` 兜成规范响应

#### Step 4：改造 Service 方法

```java
@Override
@RequireSpaceRole(SpaceRole.OWNER)
public void deleteSpace(@SpaceId Long spaceId, LoginUser loginUser) {
    spaceMapper.deleteById(spaceId);   // 一行业务
}

@Override
@RequireSpaceRole({SpaceRole.OWNER, SpaceRole.ADMIN})
public void updateSpace(@SpaceId Long spaceId, UpdateSpaceDTO dto, LoginUser loginUser) {
    Space space = checkSpace(spaceId);  // 这里还需要拿对象，所以保留
    space.setName(dto.getName());
    space.setDescription(dto.getDescription());
    spaceMapper.updateById(space);
}
```

不是所有方法都适合切面：

| 方法 | 是否注解化 | 原因 |
|------|----------|------|
| `deleteSpace` | ✅ | 纯角色校验 |
| `updateSpace` | ✅ | 纯角色校验 |
| `addMember` | ✅ | 纯角色校验 |
| `updateMemberRole` | ✅ | 纯角色校验 |
| `removeMember` | ❌ | 权限逻辑定制（ADMIN 不能踢 ADMIN），注解表达不了 |
| `getSpaceById` / `listMembers` | ❌ | 校验"是否成员"，跟角色无关 |

**原则**：注解只替代"检查角色 → 放行/拒绝"这种纯粹模式。复杂权限手写更清晰，强行套注解只会引入新的概念（注解 + 表达式 + 切面参数），反而难懂。

注解只替换了 `checkRole` 调用，原 `checkRole` 私有方法删除；`checkSpace` / `checkIsMember` 在非注解方法里还在用，保留。

### 改造前后对比

改造前 `deleteSpace`：

```java
public void deleteSpace(Long spaceId, LoginUser loginUser) {
    checkSpace(spaceId);
    checkRole(spaceId, loginUser, SpaceRole.OWNER);
    spaceMapper.deleteById(spaceId);
}
```

改造后：

```java
@RequireSpaceRole(SpaceRole.OWNER)
public void deleteSpace(@SpaceId Long spaceId, LoginUser loginUser) {
    spaceMapper.deleteById(spaceId);
}
```

收益：
- 业务方法只剩业务逻辑，权限规则上移到注解
- 改权限规则只改注解，不动方法体
- 看 Service 类一眼能看出每个方法的权限要求

代价：
- 调试时栈帧多一层切面，新人需要理解 AOP 才能看懂校验在哪
- 切面里有反射和泛型转型（`(Long) args[i]`），运行时才报错；注解贴错位置编译期发现不了

### 过程中踩的坑

**1. `@RequireSpaceRole` 的 `@Target` 写成 `PARAMETER`**

复制粘贴 `@SpaceId` 时连 `@Target(ElementType.PARAMETER)` 一起复制了，结果注解只能贴在参数上，贴方法上编译报错。**记忆点**：每写一个注解，先问自己"它该出现在哪种语法位置"，对应改 `@Target`。

**2. 切面里取出注解的 roles 但没用，校验时仍写死 `OWNER + ADMIN`**

```java
SpaceRole[] roles = requireSpaceRole.value();   // 取了
// ...
SpaceRole[] rolesToCheck = new SpaceRole[]{SpaceRole.OWNER, SpaceRole.ADMIN}; // 又写死
.in(SpaceMember::getRole, Arrays.asList(rolesToCheck));   // 用的是写死的
```

**后果**：`@RequireSpaceRole(SpaceRole.OWNER)` 实际允许 ADMIN 通过。注解形同虚设，所有方法权限都变成"OWNER 和 ADMIN 都能过"。**记忆点**：从注解读出来的值必须用上，否则注解参数就是装饰品。这种 bug 编译不报错、单元测试要专门构造 ADMIN 用例才发现，最坑。

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

## 待澄清的问题

（开发中填写）
