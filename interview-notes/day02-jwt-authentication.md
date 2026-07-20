# Day 2：JWT 登录认证流程

> 日期：2026-07-20
> 项目：TeamDocs
> 目标：能讲清 JWT 的结构、登录签发、请求校验、优缺点，以及 TeamDocs 当前的认证实现。

---

## 1. 今日主题

今天学习：**JWT 登录认证流程**。

Day 1 已经讲过请求如何经过 Controller、Service 和 Mapper。今天沿着登录请求继续往下追：用户登录成功后为什么要生成 token，后续请求如何携带 token，后端又如何识别当前用户。

---

## 2. 今日对应项目文件

按顺序阅读：

```text
teamdocs-backend/src/main/java/asia/creat/controller/UserController.java
teamdocs-backend/src/main/java/asia/creat/service/impl/UserServiceImpl.java
teamdocs-backend/src/main/java/asia/creat/utils/JWTUtils.java
teamdocs-backend/src/main/java/asia/creat/filter/JwtAuthenticationFilter.java
teamdocs-backend/src/main/java/asia/creat/config/SecurityConfig.java
teamdocs-backend/src/main/java/asia/creat/security/LoginUser.java
teamdocs-backend/src/main/resources/application.yaml
```

---

## 3. JWT 是什么？

JWT 全称是 JSON Web Token，是一种紧凑、自包含的令牌格式，常用于前后端分离项目的身份认证。

一个 JWT 通常由三部分组成：

```text
Header.Payload.Signature
```

### 3.1 Header

Header 描述 token 类型和签名算法，例如：

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

### 3.2 Payload

Payload 保存声明，也就是 claims，例如用户 ID、用户名和过期时间。

TeamDocs 登录时写入：

```java
claims.put("userId", user.getId());
claims.put("username", user.getUsername());
```

注意：Payload 只是 Base64URL 编码，**不是加密**。任何拿到 token 的人都可以解码查看内容，所以不能放密码、身份证号等敏感信息。

### 3.3 Signature

Signature 用来防止 token 被篡改。

后端使用 Header、Payload 和密钥计算签名。攻击者即使修改了 Payload，因为不知道密钥，也无法生成正确签名，后端校验时就会拒绝。

一句话记忆：

> JWT 的 Payload 可读，Signature 防篡改；JWT 默认不是加密方案。

---

## 4. TeamDocs 的 JWT 完整流程

### 4.1 登录签发 token

`POST /user/login` 的流程：

```text
UserController.login
 -> UserServiceImpl.login
 -> 根据 username 查询 User
 -> BCrypt 校验密码
 -> 组装 userId、username claims
 -> JWTUtils.generateJWT
 -> 使用 JWT_SECRET 签名
 -> 返回 token
```

`JWTUtils.generateJWT` 的核心工作：

```java
return Jwts.builder()
        .claims(claims)
        .expiration(new Date(System.currentTimeMillis() + expirationTime))
        .signWith(getSecretKey())
        .compact();
```

项目配置的有效期为：

```yaml
jwt:
  expiration: 604800000
```

`604800000` 毫秒等于 7 天。

### 4.2 客户端携带 token

登录成功后，客户端在后续请求头中携带：

```http
Authorization: Bearer <token>
```

`Bearer` 后面必须有一个空格。

### 4.3 Filter 校验 token

`JwtAuthenticationFilter` 处理非登录/注册请求：

```text
读取 Authorization 请求头
 -> 检查 Bearer 前缀
 -> 截取 token
 -> JWTUtils.parseToken 校验签名和过期时间
 -> 从 claims 取出 userId、username
 -> 构造 LoginUser
 -> 构造 Authentication
 -> 放入 SecurityContextHolder
 -> 放行请求
```

如果 token 缺失、签名错误或已经过期，Filter 当前直接设置 HTTP 401 并结束请求。

### 4.4 Controller 获取登录用户

Filter 把 `LoginUser` 放进认证对象后，Controller 可以这样获取：

```java
@GetMapping("/info")
public Result info(@AuthenticationPrincipal LoginUser loginUser) {
    return Result.success(loginUser);
}
```

`@AuthenticationPrincipal` 取出的就是 Authentication 中的 principal。

---

## 5. JWT 和 Session 的区别

| 对比项 | JWT | Session |
|---|---|---|
| 状态保存 | 主要由客户端保存 token | 服务端保存会话数据 |
| 服务端扩容 | 天然更方便横向扩容 | 多实例需要共享 Session 或粘性会话 |
| 主动失效 | 较难，通常要黑名单或缩短有效期 | 服务端删除 Session 即可 |
| 携带数据量 | token 每次请求都会传输 | 客户端通常只传 sessionId |
| 安全重点 | 密钥、过期时间、存储位置 | sessionId 防盗、Session 存储安全 |

面试时不要说“JWT 一定比 Session 好”。正确说法是：

> JWT 更适合无状态、分布式和前后端分离场景，但主动注销和权限变更后的即时失效更麻烦；Session 更容易由服务端控制失效，但多实例部署需要共享会话。

---

## 6. JWT 的优点和缺点

### 优点

1. 服务端不必保存每个用户的会话状态。
2. 适合多实例和微服务间传递身份。
3. token 自包含，可以携带少量用户声明。
4. 前后端分离使用方便。

### 缺点

1. 签发后难以主动撤销。
2. token 被盗后，在过期前可能一直有效。
3. Payload 可读，不能存敏感信息。
4. claims 太多会让每次请求都变大。
5. 权限发生变化时，旧 token 中的信息可能过时。

常见改进：

- Access Token 设置较短有效期。
- 配合 Refresh Token 刷新登录状态。
- Redis 保存黑名单、token 版本号或用户登录版本。
- 敏感操作再次验证身份。
- 全程使用 HTTPS。

---

## 7. TeamDocs 当前实现的真实取舍

### 7.1 `STATELESS`

`SecurityConfig` 中配置：

```java
.sessionManagement(s ->
        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

表示 Spring Security 不创建和使用 HTTP Session 保存认证状态，每次请求都要重新解析 JWT。

### 7.2 当前授权规则是 `permitAll`

项目当前配置：

```java
.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
```

这表示 Spring Security 的授权规则本身没有要求“必须认证”。项目实际依赖自定义 JWT Filter：除登录和注册外，缺 token 就直接返回 401。

面试中可以如实说：

> 当前版本为了先跑通 JWT 链路，授权规则配置成了 `permitAll`，认证门槛由自定义 Filter 控制。更标准的做法是用 `requestMatchers` 放行登录注册，再配置 `anyRequest().authenticated()`，让认证规则集中在 Security 配置中。

### 7.3 白名单匹配可以更精确

当前代码使用：

```java
requestURI.contains("/user/login")
```

它简单但可能误匹配包含该字符串的其他路径。更成熟的实现可以使用精确路径、`RequestMatcher`，或直接在 Security 配置中管理白名单。

---

## 8. 面试回答模板

### 问题：介绍一下你项目中的 JWT 认证流程。

> 我的 TeamDocs 项目采用 JWT 做无状态认证。用户调用登录接口后，Service 先根据用户名查询用户，再用 BCrypt 校验密码。校验成功后，把用户 ID 和用户名放进 claims，使用服务端密钥生成带过期时间的 JWT。客户端后续把 token 放在 `Authorization: Bearer` 请求头里。请求进入后端时，继承 `OncePerRequestFilter` 的 JWT Filter 会提取 token，验证签名和过期时间，再从 claims 构造 `LoginUser` 和 `UsernamePasswordAuthenticationToken`，最后放入 `SecurityContextHolder`。Controller 可以通过 `@AuthenticationPrincipal` 获取当前用户。项目使用 `STATELESS`，因此服务端不通过 Session 保存登录状态。

### 追问：JWT 为什么不能存密码？

> JWT 的 Payload 默认只是 Base64URL 编码，并没有加密。拿到 token 的人可以解码看到 Payload，所以只能放非敏感、必要的信息。签名只能防止内容被篡改，不能隐藏内容。

### 追问：用户退出登录后，JWT 怎么立即失效？

> 单纯的无状态 JWT 无法天然做到立即失效。可以缩短 Access Token 有效期，并使用 Refresh Token；也可以在 Redis 中保存黑名单、用户 tokenVersion 或最后注销时间，每次认证时额外检查。代价是重新引入了一部分服务端状态。

---

## 9. 自测题与答案

### 题 1：JWT 由哪三部分组成？

答案：Header、Payload、Signature。Header 描述算法和类型，Payload 保存 claims，Signature 用于校验 token 是否被篡改。

### 题 2：JWT 是加密的吗？

答案：普通 JWT（JWS）不是加密的。Header 和 Payload 可以被解码查看，Signature 只负责防篡改。需要隐藏内容时要使用额外加密方案，但认证 token 通常只放必要的非敏感声明。

### 题 3：TeamDocs 在哪里生成 JWT？

答案：`UserServiceImpl.login` 完成用户和密码校验后组装 claims，再调用 `JWTUtils.generateJWT` 生成 token。

### 题 4：TeamDocs 在哪里解析 JWT？

答案：`JwtAuthenticationFilter` 从请求头取出 token，调用 `JWTUtils.parseToken` 校验签名和过期时间，并读取 claims。

### 题 5：JWT 和 Session 最大的取舍是什么？

答案：JWT 更方便无状态扩容，但主动撤销较麻烦；Session 更容易由服务端控制失效，但多实例环境要解决会话共享问题。

---

## 10. 今日小练习与参考答案

### 练习 1

请求 `/user/info` 时，写出从请求头到 Controller 参数的链路。

参考答案：

```text
Authorization: Bearer token
 -> JwtAuthenticationFilter 提取 token
 -> JWTUtils.parseToken
 -> 读取 userId、username
 -> new LoginUser(...)
 -> new UsernamePasswordAuthenticationToken(...)
 -> SecurityContextHolder.setAuthentication(...)
 -> @AuthenticationPrincipal 注入 LoginUser
 -> UserController.info
```

### 练习 2

解释 token 缺失和 token 过期时项目当前分别如何处理。

参考答案：

- token 缺失或格式错误：Filter 设置 HTTP 401，直接 `return`。
- token 过期或签名校验失败：`parseToken` 抛异常，Filter 捕获后设置 HTTP 401，直接 `return`。
- 两种情况都不会继续调用 `filterChain.doFilter`。

---

## 11. 今日最终话术

> TeamDocs 使用 JWT 实现无状态认证。登录成功后，后端使用密钥对包含用户 ID、用户名和过期时间的 token 签名；客户端后续通过 Bearer 请求头携带 token。JWT Filter 在请求进入 Controller 前完成解析和校验，把当前用户放入 SecurityContext，Controller 再通过 `@AuthenticationPrincipal` 获取用户。JWT 方便服务横向扩容，但主动注销较难，后续可以通过短期 Access Token、Refresh Token 和 Redis tokenVersion 改进。

---

## 12. 明日预告

Day 3 学习：**Filter、Interceptor、AOP 的区别**。

明天会结合：

```text
JwtAuthenticationFilter
SpaceRoleAspect
OperationLogAspect
```

重点回答：三者分别拦截哪一层、执行顺序是什么、认证和操作日志为什么放在不同位置。
