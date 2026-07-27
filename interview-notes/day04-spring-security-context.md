# Day 4：Spring Security 与 SecurityContext

> 日期：2026-07-20
> 项目：TeamDocs
> 目标：理解认证、授权、Authentication、SecurityContextHolder 和 `@AuthenticationPrincipal` 的关系，并能评价 TeamDocs 当前安全配置。

---

## 1. 今日主题

JWT 负责证明“这个 token 没被篡改且没有过期”，Spring Security 则负责在一次请求中保存和使用“当前用户是谁”。

今天重点串起这条链路：

```text
JWT claims
 -> LoginUser
 -> Authentication
 -> SecurityContext
 -> @AuthenticationPrincipal
```

---

## 2. 今日对应项目文件

```text
teamdocs-backend/src/main/java/asia/creat/config/SecurityConfig.java
teamdocs-backend/src/main/java/asia/creat/filter/JwtAuthenticationFilter.java
teamdocs-backend/src/main/java/asia/creat/security/LoginUser.java
teamdocs-backend/src/main/java/asia/creat/security/SpaceContext.java
teamdocs-backend/src/main/java/asia/creat/controller/UserController.java
teamdocs-backend/src/main/java/asia/creat/aspect/SpaceRoleAspect.java
```

---

## 3. 认证和授权的区别

### 认证 Authentication

认证回答：**你是谁？**

例如：

- 用户名密码登录
- JWT 校验
- 短信验证码
- OAuth2 登录

TeamDocs 中，JWT Filter 校验 token 并建立 Authentication，完成的是认证。

### 授权 Authorization

授权回答：**你能做什么？**

例如：

- 是否允许访问某个接口
- 是否是空间成员
- 是否拥有 OWNER 或 ADMIN 角色

TeamDocs 中，`SpaceRoleAspect` 根据 `SpaceMember.role` 判断能否执行 Service 方法，完成的是业务授权。

一句话：

> 认证确认身份，授权检查权限；必须先知道你是谁，才能判断你能做什么。

---

## 4. Authentication 是什么？

`Authentication` 是 Spring Security 表示当前认证信息的核心接口，常见内容包括：

- `principal`：当前主体，通常是用户对象。
- `credentials`：凭证，例如密码或 token；认证后通常不再保留敏感凭证。
- `authorities`：权限或角色集合。
- `authenticated`：是否已经通过认证。

TeamDocs 构造的是：

```java
UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
                loginUser,
                null,
                Collections.emptyList()
        );
```

在这里：

- principal 是 `LoginUser`。
- credentials 是 `null`。
- authorities 是空集合。
- 使用带 authorities 的构造方法，表示该 Authentication 已通过认证。

项目没有把空间 OWNER/ADMIN/MEMBER 放进 authorities，因为空间角色是“每个空间不同”的动态业务权限，由 `SpaceRoleAspect` 查询数据库判断。

---

## 5. SecurityContext 和 SecurityContextHolder

### 5.1 SecurityContext

`SecurityContext` 是保存当前请求 Authentication 的容器。

核心关系：

```text
SecurityContext
 └── Authentication
      ├── principal = LoginUser
      ├── credentials = null
      └── authorities = []
```

### 5.2 SecurityContextHolder

`SecurityContextHolder` 提供对当前线程 SecurityContext 的访问：

```java
SecurityContextHolder.getContext().setAuthentication(auth);
```

后面的 Controller、Service 或 Spring Security 组件就可以获取当前认证信息。

默认情况下，它通常基于 ThreadLocal 保存上下文，因此同一个请求处理线程中的代码都能访问当前用户，而不同线程之间互不干扰。

### 5.3 ThreadLocal 的注意点

ThreadLocal 数据必须在请求结束后清理，否则线程池复用线程时可能发生用户数据串线。

Spring Security 的过滤器链负责管理 SecurityContext 生命周期。项目自定义的 `SpaceContext` 也使用 ThreadLocal，因此 `SpaceRoleAspect` 明确在 `finally` 中执行：

```java
SpaceContext.clear();
```

异步任务或新线程不会天然继承普通 ThreadLocal 中的认证信息。需要异步执行时，应该显式传递用户信息，或使用 Spring Security 提供的上下文传播方案，不能默认新线程里还能取到当前用户。

---

## 6. `@AuthenticationPrincipal` 为什么能取到 LoginUser？

JWT Filter 先把 `LoginUser` 放入 Authentication 的 principal：

```java
new UsernamePasswordAuthenticationToken(loginUser, null, ...)
```

然后把 Authentication 放入 SecurityContext：

```java
SecurityContextHolder.getContext().setAuthentication(auth);
```

Controller 参数使用：

```java
@AuthenticationPrincipal LoginUser loginUser
```

Spring MVC 的参数解析器会读取当前 SecurityContext 中的 Authentication，再取出 principal 注入参数。

所以它不是从请求 JSON、query 参数或数据库直接得到用户，而是从当前认证上下文中得到。

---

## 7. SecurityFilterChain

TeamDocs 通过 Bean 配置安全过滤器链：

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception
```

当前核心配置：

```java
http
    .csrf(csrf -> csrf.disable())
    .sessionManagement(s ->
        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
    .addFilterBefore(jwtAuthenticationFilter,
        UsernamePasswordAuthenticationFilter.class);
```

### 7.1 `STATELESS`

Spring Security 不使用 Session 持久化认证。每个请求必须携带 JWT，并由 Filter 重新建立 SecurityContext。

### 7.2 `addFilterBefore`

把自定义 JWT Filter 放在 `UsernamePasswordAuthenticationFilter` 之前，使 Bearer Token 能较早被解析并建立认证。

### 7.3 为什么关闭 Filter 自动注册？

`JwtAuthenticationFilter` 同时是 `@Component`，Spring Boot 可能把它作为普通 Servlet Filter 自动注册；SecurityConfig 又把它加入 Security Filter Chain。

项目通过：

```java
registration.setEnabled(false);
```

关闭普通 Servlet Filter 的自动注册，只保留 Security Filter Chain 中的执行，避免同一个 Filter 执行两次。

---

## 8. 当前 `permitAll` 的含义

```java
.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
```

表示 Spring Security 授权阶段允许所有请求通过。当前项目实际依靠 JWT Filter 自己判断：

- `/user/login`、`/user/register`：放行。
- 其他请求缺少或无法解析 token：返回 401。
- token 有效：建立认证后放行。

这种实现能够工作，但职责不够集中。更标准的配置思路是：

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/user/login", "/user/register").permitAll()
    .anyRequest().authenticated()
)
```

同时让 JWT Filter 在没有 token 时不要替代整个授权系统做所有决策，而是由 Spring Security 的异常处理入口统一返回 401。

面试时要讲项目现状，也要讲自己知道的改进方向。

---

## 9. 401 和 403 的区别

### HTTP 401 Unauthorized

虽然英文叫 Unauthorized，但更准确地理解为“未认证”：

- 没有 token
- token 格式错误
- token 过期
- token 签名无效

TeamDocs 的 JWT Filter 在这些情况下返回 401。

### HTTP 403 Forbidden

表示用户身份已经确认，但没有执行该操作的权限：

- MEMBER 尝试删除空间
- 非管理员尝试添加成员

TeamDocs 当前空间权限失败会抛 `BusinessException`，再由全局异常处理包装为 `Result.error`，HTTP 状态码不一定是 403。这是当前实现与标准 HTTP 语义之间的差距。

一句话记忆：

> 401 是“你还没证明自己是谁”，403 是“我知道你是谁，但你不能做这件事”。

---

## 10. 为什么无状态 JWT 常关闭 CSRF？

CSRF 利用浏览器自动携带 Cookie 的特性冒充用户发请求。

如果 JWT 只保存在客户端代码可控的位置，并通过 `Authorization` Header 主动发送，浏览器不会像 Cookie 一样自动附带该 Header，因此传统 CSRF 风险较低，前后端分离的无状态 API 常关闭 CSRF。

但不能死背“JWT 就没有 CSRF”：

> 如果 JWT 存在 Cookie 中并由浏览器自动携带，仍要考虑 CSRF 防护；同时把 token 放在可被 JavaScript 读取的位置又需要重点防 XSS。

安全方案需要同时考虑 token 存储、XSS、CSRF 和 HTTPS。

---

## 11. 项目中的两种上下文

| 上下文 | 保存内容 | 建立位置 | 使用范围 | 清理方式 |
|---|---|---|---|---|
| `SecurityContext` | 当前登录用户 Authentication | JWT Filter | 整个请求认证链路 | Spring Security 管理 |
| `SpaceContext` | 当前用户在某空间的 SpaceMember | SpaceRoleAspect | 已通过空间角色校验的业务调用 | 切面 `finally` 主动 clear |

为什么不把它们合并？

- 登录身份对整个请求稳定。
- 空间角色依赖具体 `spaceId`，同一个用户在不同空间可能有不同角色。
- `SpaceContext` 是更细粒度的业务上下文。

---

## 12. 面试回答模板

### 问题：Spring Security 如何保存当前用户？

> Spring Security 使用 SecurityContext 保存当前 Authentication，并通过 SecurityContextHolder 提供访问。我的 TeamDocs 项目在 JWT Filter 中解析 token，构造包含 `LoginUser` principal 的 `UsernamePasswordAuthenticationToken`，然后放入 SecurityContext。后续 Controller 使用 `@AuthenticationPrincipal` 就可以取出这个 principal。项目配置为 `STATELESS`，所以每个请求都会重新解析 JWT，不依赖 Session 保存认证状态。

### 追问：为什么 authorities 是空的？

> TeamDocs 的 OWNER、ADMIN、MEMBER 是空间维度的动态角色，同一个用户在不同空间可能不同，不能简单当作全局角色放入 token。项目在 Service AOP 中根据 spaceId 查询 `space_member` 表进行授权。后续如果有全局管理员角色，可以放入 authorities 并使用 Spring Security 的方法授权。

### 追问：SecurityContext 是线程安全的吗？

> 默认策略通常基于 ThreadLocal，让每个请求线程持有自己的上下文。重点不是多个线程共享一个可变对象，而是线程隔离和请求结束后的清理。异步线程不会自动拥有同样的上下文，需要显式传播。

---

## 13. 自测题与答案

### 题 1：Authentication 中常见的四类信息是什么？

答案：principal、credentials、authorities、authenticated 状态。

### 题 2：`@AuthenticationPrincipal` 读取的是什么？

答案：读取当前 SecurityContext 中 Authentication 的 principal。TeamDocs 中 principal 是 `LoginUser`。

### 题 3：`STATELESS` 有什么影响？

答案：Spring Security 不使用 Session 保存认证状态，每个请求都要携带并重新校验 JWT，然后重新建立 SecurityContext。

### 题 4：401 和 403 有什么区别？

答案：401 表示未通过身份认证；403 表示身份已确认，但权限不足。

### 题 5：为什么 `SpaceContext` 不能代替 SecurityContext？

答案：SecurityContext 表示全局登录身份；SpaceContext 表示用户在某个具体空间中的成员和角色信息，依赖当前业务 spaceId，生命周期和含义都不同。

---

## 14. 今日小练习与参考答案

### 练习 1

解释 `/user/info` 为什么不需要 Controller 手动解析 Authorization Header。

参考答案：

JWT Filter 已经在 Controller 前解析 Header、校验 token，并把 `LoginUser` 放入 SecurityContext。Spring MVC 再通过 `@AuthenticationPrincipal` 参数解析器把 principal 注入 Controller，所以 Controller 不需要重复处理 token。

### 练习 2

判断下面场景应该返回 401 还是 403：

1. 请求没有 token。
2. token 已过期。
3. MEMBER 尝试执行仅 OWNER 可执行的操作。
4. token 有效，但用户不是目标空间成员。

参考答案：

1. 401。
2. 401。
3. 403。
4. 通常按已认证但无资源权限处理为 403；有些系统为避免暴露资源存在性也可能返回 404，需要统一 API 约定。

---

## 15. 今日最终话术

> TeamDocs 的 JWT Filter 负责认证：解析 token 后，把 `LoginUser` 作为 principal 放入 Authentication，再写入 SecurityContextHolder。Controller 通过 `@AuthenticationPrincipal` 获取当前用户。空间 OWNER、ADMIN、MEMBER 属于资源级动态权限，不放在全局 authorities 中，而是由空间权限 AOP 根据 spaceId 查询数据库授权。项目采用 STATELESS，所以每次请求都会重新认证。

---

## 16. 明日预告

Day 5 学习：**统一响应与全局异常处理**。

明天会结合：

```text
Result
BusinessException
GlobalExceptionHandler
@Validated
MethodArgumentNotValidException
```

重点回答：为什么要统一响应、业务异常为什么继承 RuntimeException、全局异常如何匹配，以及 HTTP 状态码和业务码的区别。
