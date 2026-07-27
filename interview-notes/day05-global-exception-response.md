# Day 5：统一响应与全局异常处理

> 日期：2026-07-20
> 项目：TeamDocs
> 目标：理解统一响应、业务异常、参数校验和全局异常处理，并能区分 HTTP 状态码与业务状态码。

---

## 1. 今日主题

如果每个 Controller 都自己写 `try-catch`，接口代码会充满重复逻辑；如果每个接口返回格式都不同，前端也很难统一处理。

TeamDocs 使用三个组件解决这类问题：

```text
Result                     统一响应格式
BusinessException          表达可预期的业务失败
GlobalExceptionHandler     集中捕获并转换异常
```

---

## 2. 今日对应项目文件

```text
teamdocs-backend/src/main/java/asia/creat/common/Result.java
teamdocs-backend/src/main/java/asia/creat/common/exception/BusinessException.java
teamdocs-backend/src/main/java/asia/creat/common/exception/GlobalExceptionHandler.java
teamdocs-backend/src/main/java/asia/creat/controller/UserController.java
teamdocs-backend/src/main/java/asia/creat/dto/UserLoginDTO.java
teamdocs-backend/src/main/java/asia/creat/service/impl/UserServiceImpl.java
```

---

## 3. 为什么需要统一响应？

TeamDocs 的 `Result` 包含：

```java
private int code;
private String msg;
private Object data;
```

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": {}
}
```

失败响应：

```json
{
  "code": 0,
  "msg": "用户名或密码错误",
  "data": null
}
```

统一响应的主要价值：

1. 前端使用同一套解析逻辑。
2. 成功和失败都有稳定字段。
3. 全局异常可以转换成同样格式。
4. 后续可以扩展错误码、traceId、分页数据。

统一响应不代表所有接口必须返回同一种业务对象，而是最外层协议保持一致。

---

## 4. HTTP 状态码与业务状态码

这是高频追问。

### 4.1 HTTP 状态码

HTTP 协议层面的结果：

- 200：请求成功处理。
- 400：请求参数错误。
- 401：未认证。
- 403：无权限。
- 404：资源不存在。
- 500：服务器内部异常。

### 4.2 业务状态码

响应 JSON 中由系统自己定义的 code，例如 TeamDocs 当前：

- `code = 1`：业务成功。
- `code = 0`：业务失败。

### 4.3 TeamDocs 当前行为

`GlobalExceptionHandler` 返回的是普通 `Result`，没有使用 `ResponseEntity` 或 `@ResponseStatus` 设置 HTTP 状态码。

因此很多业务异常和参数异常虽然响应体中 `code = 0`，HTTP 状态仍可能是 200。

面试时可以这样评价：

> 当前版本先使用 1/0 业务码统一前端处理，但 HTTP 语义还可以完善。更成熟的实现会同时返回合适的 HTTP 状态码和细分业务错误码，例如参数错误返回 400、未认证返回 401、无权限返回 403，响应体再提供稳定的业务 code 和 message。

---

## 5. BusinessException

项目定义：

```java
public class BusinessException extends RuntimeException
```

业务异常表示“系统正常运行时可以预期的业务失败”，例如：

- 用户名已存在。
- 用户名或密码错误。
- 空间不存在。
- 不是空间成员。
- 没有目标操作权限。

### 5.1 为什么继承 RuntimeException？

`RuntimeException` 是非受检异常，不要求每一层方法都写 `throws` 或强制捕获。

业务层可以直接：

```java
throw new BusinessException("用户名已存在");
```

异常沿调用栈向上传播，最后由全局异常处理器统一转换成响应。

### 5.2 为什么不在 Controller 中捕获？

如果每个 Controller 都写：

```java
try {
    service.doSomething();
} catch (BusinessException e) {
    return Result.error(e.getMessage());
}
```

会产生大量重复代码，也容易让不同接口处理不一致。全局处理器把异常转换逻辑集中在一个位置。

### 5.3 业务异常和系统异常的区别

| 类型 | 示例 | 是否可预期 | 返回信息 |
|---|---|---|---|
| 业务异常 | 用户名已存在、权限不足 | 是 | 可以给用户明确提示 |
| 参数异常 | username 为空、长度非法 | 是 | 返回字段校验信息 |
| 系统异常 | 空指针、数据库连接失败 | 通常否 | 对外返回通用提示，内部记录详细日志 |

不能把所有异常原始 message 都直接返回前端，否则可能泄露 SQL、路径、配置或实现细节。

---

## 6. `@RestControllerAdvice`

项目使用：

```java
@RestControllerAdvice
public class GlobalExceptionHandler
```

它可以理解为：

```text
@ControllerAdvice + @ResponseBody
```

- `@ControllerAdvice`：对多个 Controller 提供全局增强。
- `@ResponseBody`：处理结果直接写入响应体并序列化为 JSON。

因此异常处理方法返回 `Result` 后，Spring MVC 会把它转成 JSON。

---

## 7. `@ExceptionHandler` 如何匹配？

TeamDocs 有三类处理：

```java
@ExceptionHandler(BusinessException.class)
```

处理业务异常。

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
```

处理请求体参数校验失败。

```java
@ExceptionHandler
public Result handleException(Exception e)
```

兜底处理其他 Exception。

当一个异常同时可以匹配多个处理器时，Spring 会选择更具体的异常类型。例如 `BusinessException` 既是 `RuntimeException`，也是 `Exception`，但会优先进入专门的 `handleBusinessException`，而不是通用兜底处理器。

兜底处理器必须保留，因为不能预先枚举所有系统异常；但它对外应该返回通用信息，对内记录完整堆栈。

---

## 8. 参数校验流程

Controller 中：

```java
public Result login(
        @RequestBody @Validated UserLoginDTO dto)
```

DTO 字段使用 Jakarta Validation 注解。项目中的 `UserLoginDTO` 使用 `@NotBlank`，`UserRegisterDTO` 还使用 `@Size` 限制长度，例如：

```java
@NotBlank(message = "账号不能为空")
@Size(min = 2, max = 16, message = "账号长度必须在2-16之间")
private String username;
```

请求流程：

```text
JSON 请求体
 -> Jackson 反序列化 DTO
 -> @Validated 触发 Bean Validation
 -> 校验通过：进入 Controller
 -> 校验失败：抛 MethodArgumentNotValidException
 -> GlobalExceptionHandler 收集字段错误
 -> 返回 Result.error
```

TeamDocs 使用 Stream 拼接字段错误：

```java
String msg = e.getBindingResult().getFieldErrors().stream()
        .map(err -> err.getField() + ": " + err.getDefaultMessage())
        .collect(Collectors.joining("; "));
```

这样一次可以返回多个字段错误，而不是只返回第一个。

---

## 9. 不同参数位置可能抛不同异常

`MethodArgumentNotValidException` 主要对应 `@RequestBody` DTO 校验。

其他场景可能出现：

- `ConstraintViolationException`：方法参数约束校验。
- `BindException`：表单或对象绑定失败。
- `MethodArgumentTypeMismatchException`：路径或 query 参数类型转换失败。
- `HttpMessageNotReadableException`：JSON 格式错误或请求体无法读取。

所以成熟项目通常会按接口实际情况逐步补齐处理器，而不是一开始为所有理论异常写一大堆代码。

---

## 10. 日志应该怎么记录？

TeamDocs 当前对业务异常和参数异常也使用 `log.error`。

日志级别可以按影响区分：

- 业务校验失败：通常是 `warn` 或 `info`，不一定代表系统故障。
- 参数校验失败：通常是 `warn`。
- 未预期系统异常：使用 `error`，记录完整堆栈。

面试回答：

> 日志级别应区分预期业务失败和系统故障，否则大量正常的业务拒绝会污染 error 告警。对外不暴露堆栈，对内通过日志和 traceId 定位。

---

## 11. 统一错误码应该如何设计？

TeamDocs 当前只有 1 和 0，适合早期开发，但前端无法仅凭 code 区分用户名重复、权限不足和参数错误。

后续可以细分，例如：

```text
0       成功（也可以保留项目现有的 1）
1001    参数错误
2001    用户不存在或密码错误
2002    token 无效
3001    空间不存在
3002    空间权限不足
5000    系统内部错误
```

关键不是编号本身，而是：

1. code 稳定，不能依赖可变的中文 message 做程序判断。
2. 错误码有统一归属和文档。
3. HTTP 状态码和业务码各自表达自己的层级。
4. 不要为了“看起来完整”过早设计几百个没人使用的错误码。

---

## 12. 完整异常链路示例

### 12.1 用户名重复

```text
POST /user/register
 -> UserController.register
 -> UserServiceImpl.register
 -> 查询到同名用户
 -> throw BusinessException("用户名已存在")
 -> GlobalExceptionHandler.handleBusinessException
 -> Result.error("业务异常：用户名已存在")
 -> Jackson 序列化 JSON
```

### 12.2 登录 DTO 校验失败

```text
POST /user/login
 -> JSON 反序列化为 UserLoginDTO
 -> @Validated 校验失败
 -> Controller 方法不执行
 -> MethodArgumentNotValidException
 -> handleMethodArgumentNotValidException
 -> 拼接字段错误
 -> Result.error(...)
```

### 12.3 数据库连接异常

```text
Mapper 查询数据库失败
 -> 异常向上传播
 -> 没有更具体的处理器
 -> handleException(Exception)
 -> 内部记录完整异常
 -> 对外返回“服务器发生异常，请稍后再试”
```

---

## 13. 面试回答模板

### 问题：你的项目如何做统一异常处理？

> TeamDocs 使用 `@RestControllerAdvice` 和 `@ExceptionHandler` 做全局异常处理。业务层遇到用户名重复、资源不存在或权限不足时抛出继承 `RuntimeException` 的 `BusinessException`，由专门处理器转换成统一 `Result`。Controller 的请求 DTO 使用 `@Validated`，字段校验失败会抛 `MethodArgumentNotValidException`，处理器收集字段错误后返回。其他未预期异常由 `Exception` 处理器兜底，对外返回通用提示，对内记录完整堆栈。这样 Controller 不需要重复写 try-catch，前端也能按统一结构处理。

### 追问：为什么 BusinessException 继承 RuntimeException？

> 业务失败需要跨 Controller、Service、Mapper 调用链向上传播。如果使用受检异常，每一层都要声明或捕获，代码噪声很大。继承 RuntimeException 可以直接抛出，再由全局处理器统一转换；同时 Spring 事务默认也会对 RuntimeException 回滚。

### 追问：统一返回后是不是所有请求都返回 HTTP 200？

> 不应该简单等同。统一响应解决的是 JSON 协议一致性，HTTP 状态码仍应表达协议层结果。项目当前不少异常确实只返回 `code = 0` 而 HTTP 仍可能是 200，这是可以优化的地方。更成熟的实现会使用 `ResponseEntity` 或异常映射返回 400、401、403、500，同时保留稳定业务码。

---

## 14. 自测题与答案

### 题 1：`@RestControllerAdvice` 有什么作用？

答案：为多个 Controller 提供全局增强，并把处理方法返回值写入响应体。常用于全局异常处理。

### 题 2：为什么业务异常不在每个 Controller 中 try-catch？

答案：会产生重复代码和不一致处理。业务层直接抛异常，全局处理器统一转换更清晰。

### 题 3：`MethodArgumentNotValidException` 一般什么时候出现？

答案：`@RequestBody` DTO 在 `@Validated` 或 `@Valid` 校验失败时，通常会抛该异常，而且 Controller 方法不会正常执行。

### 题 4：HTTP 状态码和业务码有什么区别？

答案：HTTP 状态码表示协议层处理结果，业务码表示应用内部的具体业务结果。两者可以同时存在，不应该互相完全替代。

### 题 5：为什么系统异常不能把原始 message 返回前端？

答案：原始异常可能暴露 SQL、服务器路径、依赖版本或内部实现。应该记录到内部日志，对外返回安全的通用提示和可追踪标识。

---

## 15. 今日小练习与参考答案

### 练习 1

给下面场景分类：业务异常、参数异常还是系统异常。

1. 注册用户名已经存在。
2. 登录 DTO 的 username 为空。
3. MySQL 连接超时。
4. MEMBER 删除空间。
5. 请求体不是合法 JSON。

参考答案：

1. 业务异常。
2. 参数校验异常。
3. 系统异常。
4. 业务权限异常；HTTP 语义上通常对应 403。
5. 请求解析异常，通常对应 400。

### 练习 2

解释为什么参数校验失败时 Service 不会执行。

参考答案：

Spring MVC 在调用 Controller 方法前完成请求体解析和 Bean Validation。校验失败会直接抛 `MethodArgumentNotValidException`，由异常解析流程处理，因此 Controller 方法体和后续 Service 调用都不会执行。

---

## 16. 今日最终话术

> TeamDocs 用 `Result` 统一响应结构，用 `BusinessException` 表达可预期业务失败，再由 `@RestControllerAdvice` 集中处理业务异常、参数异常和系统异常。这样 Controller 保持轻量，前端也能统一解析。当前项目主要依赖 1/0 业务码，后续应进一步补充细分错误码和正确的 400、401、403、500 HTTP 状态码。

---

## 17. 下一阶段预告

Day 6 建议学习：**Spring IoC、依赖注入与 Bean 生命周期**。

会结合项目中当前同时存在的字段注入和构造器注入，回答：

- IoC 和 DI 是什么关系？
- 为什么推荐构造器注入？
- Bean 从创建到销毁经历了什么？
- 循环依赖为什么会出现？
