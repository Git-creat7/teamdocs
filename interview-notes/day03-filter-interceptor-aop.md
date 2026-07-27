# Day 3：Filter、Interceptor、AOP 的区别

> 日期：2026-07-20
> 项目：TeamDocs
> 目标：能从作用层级、执行时机和适用场景讲清 Filter、Interceptor、AOP，并结合项目说明为什么 JWT、权限和操作日志放在不同位置。

---

## 1. 今日主题

Filter、Interceptor、AOP 都可以在“不改主业务代码”的情况下加入公共逻辑，所以非常容易混淆。

今天不只背定义，而是回答三个问题：

1. 它们分别拦截什么？
2. 一次请求中它们大致按什么顺序执行？
3. TeamDocs 为什么用 Filter 做 JWT、用 AOP 做空间权限和操作日志？

---

## 2. 今日对应项目文件

```text
teamdocs-backend/src/main/java/asia/creat/filter/JwtAuthenticationFilter.java
teamdocs-backend/src/main/java/asia/creat/config/SecurityConfig.java
teamdocs-backend/src/main/java/asia/creat/aspect/SpaceRoleAspect.java
teamdocs-backend/src/main/java/asia/creat/aspect/OperationLogAspect.java
teamdocs-backend/src/main/java/asia/creat/anno/RequireSpaceRole.java
teamdocs-backend/src/main/java/asia/creat/anno/OperationLog.java
teamdocs-backend/src/main/java/asia/creat/anno/SpaceId.java
```

项目目前没有自定义 Spring MVC Interceptor，因此 Interceptor 部分以原理和适用场景为主。

---

## 3. 三者的核心区别

| 对比项 | Filter | Interceptor | AOP |
|---|---|---|---|
| 所属体系 | Servlet 规范 | Spring MVC | Spring AOP |
| 主要拦截对象 | HTTP 请求和响应 | Controller 请求处理 | Spring Bean 方法调用 |
| 典型位置 | DispatcherServlet 之前/之外 | DispatcherServlet 内部 | Bean 代理调用前后 |
| 能否操作 request/response | 可以，最直接 | 可以 | 可以间接获取，但不是核心能力 |
| 能否拿到 Controller 方法信息 | 通常不方便 | 可以拿到 HandlerMethod | 可以拿到目标方法、参数、注解 |
| 典型场景 | 认证、CORS、编码、请求包装 | 登录检查、Controller 日志、接口耗时 | 事务、业务权限、审计日志、缓存 |
| 是否依赖 Spring Bean | Servlet Filter 不一定；项目中的 Filter 是 Bean | 依赖 Spring MVC | 目标通常必须是 Spring Bean |

一句话记忆：

> Filter 管请求入口，Interceptor 管 Controller，AOP 管 Spring Bean 方法。

---

## 4. Filter

### 4.1 Filter 是什么？

Filter 是 Servlet 规范提供的过滤器，工作在 Web 容器层。它可以在请求进入 Servlet 前处理请求，也可以在响应返回后处理响应。

常见场景：

- 身份认证
- CORS
- 请求/响应编码
- 请求体包装
- 通用访问日志
- 安全 Header

### 4.2 TeamDocs 为什么用 Filter 做 JWT？

JWT 认证需要在 Controller 执行前完成：

1. 从 HTTP Header 读取 token。
2. token 无效时直接返回 401。
3. token 有效时建立 Spring Security 认证上下文。
4. 让后面的 Controller 和 Service 都能使用当前用户。

这正好是 Filter 的职责。

项目使用 `OncePerRequestFilter`：

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter
```

它的目的不是“整个系统永远只执行一次”，而是保证在一次请求分派中按框架规则避免重复执行过滤逻辑。

### 4.3 `filterChain.doFilter` 为什么重要？

```java
filterChain.doFilter(request, response);
```

它表示把请求交给后续 Filter 或目标 Servlet。

- 调用：请求继续执行。
- 不调用并直接返回：请求链在当前 Filter 终止。
- 一次逻辑中错误地调用两次：可能导致重复处理或响应已经提交。

TeamDocs 在 token 缺失或解析失败时不继续调用，从而阻止请求进入 Controller。

---

## 5. Interceptor

### 5.1 Interceptor 是什么？

Spring MVC Interceptor 主要围绕 Controller 请求执行，常用三个方法：

```java
preHandle(...)
postHandle(...)
afterCompletion(...)
```

- `preHandle`：Controller 执行前，返回 `false` 可以中止请求。
- `postHandle`：Controller 正常执行后、响应完成前。
- `afterCompletion`：请求完成后，即使发生异常也可用于清理和统计。

### 5.2 Interceptor 适合什么？

因为它可以拿到 `HandlerMethod`，所以适合需要了解 Controller 方法信息的 Web 层逻辑：

- Controller 接口访问日志
- 接口耗时
- 基于 Controller 注解的简单校验
- Locale、租户、Web 上下文初始化

### 5.3 为什么 TeamDocs 暂时不需要 Interceptor？

- JWT 已经在 Spring Security Filter 链中处理。
- 空间角色校验发生在 Service 方法，使用 AOP 更贴近业务边界。
- 操作日志需要读取 Service 方法上的 `@OperationLog`，AOP 更直接。

如果未来要统一记录“每个 Controller 的请求路径、响应状态和总耗时”，Interceptor 会是一个合理选择。

---

## 6. AOP

### 6.1 AOP 是什么？

AOP 是面向切面编程，用于把分散在多个业务方法中的横切逻辑抽出来，例如：

- 事务
- 权限校验
- 操作日志
- 缓存
- 性能统计

Spring AOP 的核心是代理。外部调用代理对象的方法时，代理先执行切面逻辑，再决定是否调用目标方法。

### 6.2 TeamDocs 的空间权限切面

业务方法使用：

```java
@RequireSpaceRole({SpaceRole.OWNER, SpaceRole.ADMIN})
public void updateSpace(@SpaceId Long spaceId, ...) {
    // 业务逻辑
}
```

`SpaceRoleAspect` 使用环绕通知：

```java
@Around("@annotation(asia.creat.anno.RequireSpaceRole)")
```

切面会：

1. 读取方法上的允许角色。
2. 从带 `@SpaceId` 的参数读取空间 ID。
3. 从参数中读取 `LoginUser`。
4. 查询空间和成员角色。
5. 无权限时抛 `BusinessException`。
6. 有权限时调用 `pjp.proceed()`。
7. 最后清理 `SpaceContext`。

### 6.3 TeamDocs 的操作日志切面

业务方法使用：

```java
@OperationLog(value = "上传文档", resourceType = "DOCUMENT")
```

`OperationLogAspect` 会读取方法参数、请求路径、资源类型和执行耗时，并在 `finally` 中保存成功或失败记录。

这比在每个 Service 方法里手写日志保存逻辑更集中，也不污染主业务代码。

---

## 7. 一次请求中的大致执行顺序

以“更新空间”请求为例：

```text
客户端请求
 -> Servlet Filter 链
 -> JwtAuthenticationFilter 校验 JWT，建立 SecurityContext
 -> DispatcherServlet
 -> Interceptor.preHandle（如果项目注册了）
 -> SpaceController.updateSpace
 -> 调用被代理的 SpaceService
 -> OperationLogAspect 进入
 -> SpaceRoleAspect 进入并校验角色
 -> SpaceServiceImpl.updateSpace 执行业务
 -> SpaceRoleAspect 退出并清理 SpaceContext
 -> OperationLogAspect 保存操作日志
 -> Controller 返回 Result
 -> Interceptor.postHandle / afterCompletion（如果注册了）
 -> Filter 链返回响应
```

这是帮助理解的主干顺序。实际顺序还受 Spring Security 多个 Filter、AOP `@Order`、异常处理等因素影响。

---

## 8. `@Around` 和 `pjp.proceed()`

环绕通知可以在方法前后都执行逻辑：

```java
try {
    return pjp.proceed();
} finally {
    // 清理或记录
}
```

`pjp.proceed()` 表示继续执行后续切面或目标方法。

- 不调用：目标业务方法不会执行。
- 调用一次：正常。
- 调用多次：目标方法可能执行多次，通常是严重问题。

在 `SpaceRoleAspect` 中，无权限时直接抛异常，不执行 `proceed`；有权限才放行业务。

---

## 9. AOP 常见失效场景

### 9.1 同类内部调用

```java
public void methodA() {
    methodB();
}
```

如果 `methodB` 有切面注解，`methodA` 使用 `this.methodB()` 内部调用时通常没有经过代理，因此切面可能不生效。

### 9.2 目标对象不是 Spring Bean

自己 `new` 出来的对象不归 Spring 容器管理，无法使用 Spring AOP 代理。

TeamDocs 的切面同时使用：

```java
@Aspect
@Component
```

`@Aspect` 表示切面身份，`@Component` 让它进入 Spring 容器。

### 9.3 方法可代理性

Spring AOP 依赖代理机制。`private` 方法、某些 `final` 方法或类，以及绕过代理的调用，都可能让切面失效。

### 9.4 注解放错位置

TeamDocs 的 `@RequireSpaceRole` 目标是方法，`@SpaceId` 目标是参数：

```java
@Target(ElementType.METHOD)
public @interface RequireSpaceRole {}

@Target(ElementType.PARAMETER)
public @interface SpaceId {}
```

方法注解和参数注解必须配对，否则切面找不到空间 ID。

---

## 10. 多个切面的顺序

`OperationLogAspect` 标记了：

```java
@Order(1)
```

通常数值越小优先级越高。对于环绕通知，可以理解为高优先级切面包在外层：先进入，后退出。

因此操作日志切面可以覆盖权限校验和业务执行的整体过程，并记录它们抛出的异常。面试时应补一句：

> 多切面顺序不能靠猜，应该显式使用 `@Order`，并通过测试验证进入和退出顺序。

---

## 11. 面试回答模板

### 问题：Filter、Interceptor、AOP 有什么区别？

> Filter 属于 Servlet 规范，主要处理进入 Web 应用的请求和响应，位置最靠前，适合认证、CORS、编码等，所以我的 TeamDocs 项目用 JWT Filter 解析 Bearer Token。Interceptor 属于 Spring MVC，围绕 Controller 执行，可以拿到 HandlerMethod，适合接口日志、Controller 层校验。AOP 主要代理 Spring Bean 方法，适合事务、业务权限和操作日志。项目中的空间角色权限和操作日志都依赖 Service 方法及其注解，所以使用 AOP。一次典型请求大致是 Filter、DispatcherServlet、Interceptor、Controller、Service AOP、目标方法，再反向返回。

### 追问：为什么空间权限不用 Filter？

> Filter 更容易拿到 HTTP 请求，但空间权限依赖具体 Service 方法、空间 ID 参数、方法注解和业务角色。放在 Filter 里会把 URL 规则和业务权限耦合起来。AOP 可以直接读取 `@RequireSpaceRole` 和 `@SpaceId`，更贴近业务方法。

### 追问：AOP 为什么会失效？

> Spring AOP 基于代理，调用必须经过代理对象。同类内部自调用、对象不是 Spring Bean、方法不可代理或者注解位置不正确，都可能绕过切面。

---

## 12. 自测题与答案

### 题 1：JWT 认证为什么更适合放在 Filter？

答案：它需要在 Controller 前读取请求头、验证 token、失败时直接返回 401，并为后续请求建立认证上下文，属于请求入口的通用逻辑。

### 题 2：Interceptor 相比 Filter 最大的优势是什么？

答案：Interceptor 位于 Spring MVC 内部，可以更方便地获取目标 Controller 和 `HandlerMethod` 信息，适合 Controller 层的注解和接口逻辑。

### 题 3：TeamDocs 为什么用 AOP 记录操作日志？

答案：操作日志依赖 Service 方法上的 `@OperationLog`、方法参数、执行结果和异常。AOP 可以统一包裹这些方法，避免每个业务方法重复写保存日志的代码。

### 题 4：`pjp.proceed()` 有什么作用？

答案：继续执行后续切面或目标方法。不调用时业务方法不会执行，错误地调用多次可能导致业务执行多次。

### 题 5：为什么 `SpaceContext.clear()` 必须放在 `finally`？

答案：无论业务成功还是抛异常都必须清理 ThreadLocal，避免线程池复用线程时把上一个请求的数据泄漏给下一个请求。

---

## 13. 今日小练习与参考答案

### 练习 1

判断以下需求适合放在哪里：

1. 解析 JWT。
2. 统计 Controller 请求总耗时。
3. 校验 Service 方法需要 OWNER 角色。
4. 保存带业务资源 ID 的操作日志。

参考答案：

1. Filter。
2. Interceptor 较合适。
3. AOP。
4. AOP。

### 练习 2

阅读 `OperationLogAspect`，解释为什么保存日志放在 `finally`。

参考答案：

`finally` 在业务成功和异常时都会执行，因此既能记录成功操作，也能记录失败操作。切面先在 `catch` 中设置 `success = 0` 和错误信息并重新抛出异常，再在 `finally` 保存记录，不会吞掉原业务异常。

---

## 14. 今日最终话术

> Filter、Interceptor、AOP 的区别主要在作用层级。Filter 处理 Servlet 请求入口，所以 TeamDocs 用它做 JWT；Interceptor 围绕 Controller，适合接口级日志；AOP 代理 Spring Bean 方法，能读取业务方法注解和参数，所以项目用它做空间角色校验和操作日志。选择位置时要看公共逻辑依赖的是 HTTP 请求、Controller 信息，还是具体业务方法。

---

## 15. 明日预告

Day 4 学习：**Spring Security 与 SecurityContext**。

重点回答：

- Authentication 里保存什么？
- `@AuthenticationPrincipal` 为什么能取到 `LoginUser`？
- 401 和 403 有什么区别？
- `STATELESS` 对认证流程有什么影响？
