# Day 1：Spring Boot 一次请求的完整链路

> 日期：2026-07-09  
> 项目：TeamDocs  
> 目标：能结合本项目讲清楚一次 HTTP 请求在 Spring Boot 后端中的完整执行流程。

---

## 1. 今日主题

今天学习：**Spring Boot 一次请求的完整链路**。

我们不先背大段概念，而是直接从 TeamDocs 项目中的登录接口和空间接口出发，理解一次请求如何从浏览器/Postman 进入后端，经过 Spring MVC、Controller、Service、Mapper，最后查询数据库并返回 JSON。

---

## 2. 今日对应项目文件

建议按顺序阅读：

```text
teamdocs-backend/src/main/java/asia/creat/TeamdocsBackendApplication.java
teamdocs-backend/src/main/java/asia/creat/controller/UserController.java
teamdocs-backend/src/main/java/asia/creat/controller/SpaceController.java
teamdocs-backend/src/main/java/asia/creat/service/UserService.java
teamdocs-backend/src/main/java/asia/creat/service/impl/UserServiceImpl.java
teamdocs-backend/src/main/java/asia/creat/mapper/UserMapper.java
teamdocs-backend/src/main/java/asia/creat/common/Result.java
teamdocs-backend/src/main/java/asia/creat/common/exception/GlobalExceptionHandler.java
```

---

## 3. 八股核心知识点

### 3.1 `@SpringBootApplication` 做了什么？

`@SpringBootApplication` 是 Spring Boot 启动类上的核心注解，它是一个组合注解，主要包含三部分：

1. `@SpringBootConfiguration`
   - 表示当前类是 Spring Boot 的配置类。
   - 本质上可以看作一个特殊的 `@Configuration`。

2. `@EnableAutoConfiguration`
   - 开启自动配置。
   - Spring Boot 会根据当前 classpath 中的依赖和配置文件，自动装配常见组件。
   - 例如项目引入了 `spring-boot-starter-web`，Spring Boot 就会自动配置 Spring MVC、内嵌 Tomcat、Jackson 等。

3. `@ComponentScan`
   - 开启组件扫描。
   - 默认从启动类所在包开始，向下扫描 `@Controller`、`@Service`、`@Component`、`@Configuration` 等 Bean。

结合 TeamDocs：

`TeamdocsBackendApplication` 在 `asia.creat` 包下，所以 `asia.creat.controller`、`asia.creat.service`、`asia.creat.mapper`、`asia.creat.filter`、`asia.creat.aspect` 等包下的类都能被 Spring 扫描和管理。

---

### 3.2 一次 HTTP 请求的大致流程

通用流程如下：

```text
客户端
 -> Tomcat / Servlet 容器
 -> Filter 链
 -> DispatcherServlet
 -> HandlerMapping 找到 Controller 方法
 -> 参数解析 / 参数校验
 -> Controller
 -> Service
 -> Mapper
 -> MySQL
 -> Service 返回结果
 -> Controller 包装 Result
 -> Jackson 序列化成 JSON
 -> HTTP Response
```

以 TeamDocs 登录接口为例：

```text
POST /user/login
 -> JwtAuthenticationFilter 判断是登录接口，直接放行
 -> DispatcherServlet 接收请求
 -> HandlerMapping 找到 UserController.login
 -> JSON 请求体反序列化为 UserLoginDTO
 -> UserServiceImpl.login
 -> UserMapper.selectOne 查询用户
 -> PasswordEncoder.matches 校验密码
 -> JWTUtils.generateToken 生成 token
 -> Result.success(token)
 -> Jackson 序列化为 JSON 返回
```

---

### 3.3 Controller、Service、Mapper 分别负责什么？

#### Controller 层

负责 HTTP 层的事情：

- 接收请求
- 解析参数
- 参数校验
- 调用 Service
- 返回统一响应

在 TeamDocs 中，例如：

```java
@PostMapping("/login")
public Result login(@RequestBody @Validated UserLoginDTO dto) {
    String token = userService.login(dto.getUsername(), dto.getPassword());
    return Result.success(token);
}
```

Controller 不直接写登录校验逻辑，也不直接查数据库。

#### Service 层

负责业务逻辑：

- 用户名是否存在
- 密码是否正确
- 是否有权限
- 是否允许删除、移动、恢复文档
- 是否需要抛出业务异常

在 TeamDocs 中，`UserServiceImpl.login` 负责查用户、校验密码、生成 JWT。

#### Mapper 层

负责数据库访问：

- 单表 CRUD
- 条件查询
- 自定义 SQL

在 TeamDocs 中，`UserMapper` 继承 MyBatis-Plus 的 `BaseMapper<User>`，可以直接使用 `selectOne`、`insert`、`selectById` 等方法。

---

### 3.4 `@RestController` 是什么？

`@RestController` 等价于：

```java
@Controller
@ResponseBody
```

含义：

- `@Controller`：声明这是 Spring MVC 的控制器。
- `@ResponseBody`：方法返回值不走视图解析，而是直接写入 HTTP 响应体。

因为 TeamDocs 是前后端分离项目，所以 Controller 返回的是 JSON，而不是 JSP/Thymeleaf 页面，因此使用 `@RestController`。

---

### 3.5 为什么需要统一返回 `Result`？

统一返回结构可以让前端更容易处理接口结果。

例如成功时统一返回：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

好处：

1. 成功和失败响应格式一致。
2. 前端可以统一判断业务状态码。
3. 全局异常处理可以返回同样的数据结构。
4. 后续扩展 traceId、错误码、分页信息更方便。

在 TeamDocs 中，Controller 基本都返回 `Result.success(...)`。

---

## 4. 结合 TeamDocs 的面试回答模板

### 问题：说一下 Spring Boot 项目中一次请求的执行流程。

可以这样回答：

> 以我 TeamDocs 项目里的登录接口为例。请求首先进入内嵌 Tomcat，然后经过 Spring Security 的 Filter 链。因为登录接口会被 JWT Filter 判断为白名单接口，所以直接放行。之后请求进入 Spring MVC 的 DispatcherServlet，DispatcherServlet 通过 HandlerMapping 找到 `UserController.login` 方法，然后把请求体 JSON 反序列化成 `UserLoginDTO`，并执行参数校验。Controller 不直接写业务逻辑，而是调用 `UserServiceImpl.login`。Service 里通过 MyBatis-Plus 的 `UserMapper` 查询用户，再用 BCrypt 校验密码，通过后用 JWT 工具类生成 token。最后 Controller 用统一的 `Result.success(token)` 包装返回，Spring MVC 再通过 Jackson 把对象序列化成 JSON 响应给前端。

---

## 5. 常见追问与答案

### 5.1 为什么不把业务逻辑写在 Controller？

答案：

> Controller 应该保持轻量，只负责 HTTP 层的事情，比如接收参数、参数校验、调用 Service、返回结果。真正的业务规则应该写在 Service 层。这样做有几个好处：第一，业务逻辑可以复用；第二，后续更方便加事务；第三，单元测试更容易写；第四，代码职责更清晰。比如我的 TeamDocs 项目中，登录接口的 Controller 只接收 `UserLoginDTO` 并调用 Service，真正的用户查询、密码校验和 JWT 生成都在 `UserServiceImpl` 中完成。

---

### 5.2 `@RequestBody` 和 `@RequestParam` 有什么区别？

答案：

> `@RequestBody` 读取的是 HTTP 请求体，常用于接收 JSON 数据，比如登录、注册、创建空间这些 POST 请求。`@RequestParam` 读取的是 URL 查询参数或者表单参数，比如 `/documents/search?keyword=abc` 这种搜索接口。简单说，传 JSON 对象一般用 `@RequestBody`，传简单查询条件一般用 `@RequestParam`。

结合项目：

- 登录注册：`@RequestBody UserLoginDTO`
- 搜索文档：适合 `@RequestParam String keyword`

---

### 5.3 DTO、Entity、VO 有什么区别？

答案：

> DTO 面向请求输入，Entity 面向数据库表，VO 面向响应展示。DTO 用来接收前端传来的参数，比如 `UserLoginDTO`、`CreateSpaceDTO`；Entity 和数据库表结构对应，比如 `User`、`Document`、`Space`；VO 用来返回前端真正需要展示的数据，比如 `SpaceMemberVO` 可以包含成员昵称、角色等信息。这样可以避免直接暴露数据库表结构，也能让接口参数和数据库结构解耦。

---

### 5.4 `DispatcherServlet` 的作用是什么？

答案：

> `DispatcherServlet` 是 Spring MVC 的前端控制器。所有进入 Spring MVC 的请求都会先到它这里，然后它根据请求路径找到对应的 Controller 方法，完成参数解析、调用方法、处理返回值、异常处理和视图/JSON 响应。可以理解为 Spring MVC 请求分发的总入口。

---

## 6. 今日小练习

### 练习 1：写出 `/user/login` 的调用链

答案：

```text
POST /user/login
 -> JwtAuthenticationFilter 判断登录接口，放行
 -> DispatcherServlet
 -> UserController.login
 -> UserServiceImpl.login
 -> UserMapper.selectOne 查询用户
 -> PasswordEncoder.matches 校验密码
 -> JWTUtils.generateToken 生成 JWT
 -> Result.success(token)
 -> Jackson 序列化 JSON 返回
```

---

### 练习 2：在 `UserServiceImpl` 中找关键代码位置

需要找出：

1. 哪一行查重？
2. 哪一行加密密码？
3. 哪一行校验密码？
4. 哪一行生成 JWT？

参考答案：

1. 注册查重：通常是类似下面的代码：

```java
Long existUser = userMapper.selectCount(lqw);
```

作用：查询数据库中是否已经存在同名用户。

2. 密码加密：通常是类似下面的代码：

```java
passwordEncoder.encode(password)
```

作用：使用 BCrypt 对明文密码加密后再入库。

3. 登录校验密码：通常是类似下面的代码：

```java
passwordEncoder.matches(password, user.getPassword())
```

作用：把用户输入的明文密码和数据库中的 BCrypt 密文进行匹配。

4. 生成 JWT：通常是类似下面的代码：

```java
jwtUtils.generateToken(user.getId(), user.getUsername())
```

作用：登录成功后生成 token，前端后续请求携带这个 token 访问受保护接口。

---

### 练习 3：对比登录接口和创建空间接口

问题：`/user/login` 和 `/space` 创建空间接口的请求流程有什么不同？

答案：

相同点：

- 都会进入 Tomcat。
- 都会经过 Filter 链。
- 都会进入 DispatcherServlet。
- 都会被分发到对应 Controller。
- 都是 Controller 调用 Service，Service 再调用 Mapper。
- 最后都用 `Result.success(...)` 返回统一 JSON。

不同点：

1. 登录接口是匿名接口。
   - `JwtAuthenticationFilter` 中会判断 `/user/login`，然后直接放行。
   - 登录接口不需要从 token 中解析用户身份。

2. 创建空间接口需要登录用户信息。
   - 请求需要携带 `Authorization: Bearer <token>`。
   - `JwtAuthenticationFilter` 会解析 token，构造 `LoginUser`，放入 `SecurityContext`。
   - Controller 中通过 `@AuthenticationPrincipal LoginUser loginUser` 获取当前登录用户。

3. 业务逻辑不同。
   - 登录接口重点是查用户、校验密码、生成 token。
   - 创建空间接口重点是插入 `space`，并把创建者加入 `space_member`，角色为 OWNER。

---

## 7. 自测题与答案

### 题 1：`@SpringBootApplication` 由哪几个核心注解组成？

答案：

主要由这三个核心注解组成：

1. `@SpringBootConfiguration`
2. `@EnableAutoConfiguration`
3. `@ComponentScan`

其中：

- `@SpringBootConfiguration` 表示当前类是配置类。
- `@EnableAutoConfiguration` 开启自动配置。
- `@ComponentScan` 从启动类所在包向下扫描 Bean。

---

### 题 2：Spring MVC 里 `DispatcherServlet` 的作用是什么？

答案：

`DispatcherServlet` 是 Spring MVC 的前端控制器，是请求进入 Spring MVC 后的统一入口。它负责：

- 接收请求
- 根据请求路径找到 Controller 方法
- 做参数解析和类型转换
- 调用 Controller
- 处理返回值
- 触发异常处理
- 最终返回 JSON 或视图

一句话：`DispatcherServlet` 负责统一调度 Spring MVC 的请求处理流程。

---

### 题 3：Controller、Service、Mapper 的边界分别是什么？

答案：

- Controller：负责 HTTP 层，接收参数、参数校验、调用 Service、返回响应。
- Service：负责业务逻辑，比如登录校验、权限判断、文档移动、评论增删等。
- Mapper：负责数据库访问，只做 CRUD 和 SQL 查询，不写复杂业务。

结合 TeamDocs：

- `UserController.login` 接收登录请求。
- `UserServiceImpl.login` 校验用户和密码，生成 JWT。
- `UserMapper.selectOne` 查询数据库中的用户。

---

### 题 4：为什么项目要用 DTO，而不是直接用 Entity 接收请求？

答案：

因为 DTO 和 Entity 的职责不同。

DTO 是接口入参模型，Entity 是数据库表模型。如果直接用 Entity 接收请求，会有几个问题：

1. 容易暴露数据库字段。
2. 前端可能传入不该传的字段，例如 `id`、`createdAt`、`deleted`。
3. 接口参数和数据库结构强耦合，后续数据库字段调整会影响接口。
4. 参数校验不够清晰。

所以 TeamDocs 中注册、登录、创建空间等接口使用 `UserRegisterDTO`、`UserLoginDTO`、`CreateSpaceDTO` 接收请求，而不是直接使用 `User` 或 `Space`。

---

### 题 5：`@RestController` 和 `@Controller` 的区别是什么？

答案：

`@RestController` 等价于 `@Controller + @ResponseBody`。

- `@Controller`：通常用于返回页面视图。
- `@RestController`：通常用于前后端分离项目，方法返回值会直接写入响应体，并由 Jackson 转成 JSON。

TeamDocs 是前后端分离后端，所以使用 `@RestController` 返回 JSON。

---

## 8. 今日最终要背下来的项目话术

> TeamDocs 后端采用 Controller-Service-Mapper 三层结构。Controller 负责接收请求、参数校验和统一返回；Service 负责业务规则，比如登录校验、空间权限、文档移动；Mapper 基于 MyBatis-Plus 访问 MySQL。一次请求进入 Spring Boot 后，会先经过 Filter 链，再由 DispatcherServlet 分发到对应 Controller，最后由 Jackson 把统一的 `Result` 对象序列化为 JSON 返回给前端。

---

## 9. 明日预告

Day 2 学习：**JWT 登录认证流程**。

明天重点看：

```text
teamdocs-backend/src/main/java/asia/creat/filter/JwtAuthenticationFilter.java
teamdocs-backend/src/main/java/asia/creat/utils/JWTUtils.java
teamdocs-backend/src/main/java/asia/creat/config/SecurityConfig.java
teamdocs-backend/src/main/java/asia/creat/security/LoginUser.java
```

明天要准备的面试问题：

1. 为什么使用 JWT？
2. JWT 一般放在哪里？
3. 后端怎么校验 JWT？
4. JWT 过期怎么办？
5. JWT 和 Session 的区别是什么？
