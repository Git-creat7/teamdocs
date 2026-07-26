# TeamDocs 前端决策（给 Gemini 实现 / Claude 审查）

> 本文档是前端实现的**硬决策**。Gemini 按此落地；Claude 按此审查。  
> 视觉细节可发挥，接口与边界不可自创。  
> 最后同步：2026-07-25（对齐后端用户模块与空间主路径接口）

## 0. 分工

| 角色 | 职责 |
|---|---|
| Claude | 技术决策、范围裁剪、接口对齐、代码审查、否决越权实现 |
| Gemini | 前端实现（脚手架、页面、样式、联调） |
| 用户 | 最终审美拍板、部署与演示 |

审查风格：只报问题与风险，不报「还没做完」；按实习交付标准审，不按生产审计灌水。

正式代码主战场：`F:/CodeProject/TeamDocs`（`teamdocs-frontend/` 与 `teamdocs-backend/`）。  
本决策文档在仓库 `docs/frontend/DECISIONS.md`（可与主战场分离维护，**以本文件为前端契约源**）。

## 1. 技术栈（已定，不要换）

| 项 | 选择 | 备注 |
|---|---|---|
| 框架 | Vue 3 + Vite | Composition API + `<script setup>` |
| UI | Element Plus | 中文 locale |
| 路由 | Vue Router 4 | history 模式 |
| 请求 | Axios | 统一封装 |
| 状态 | 先不用 Pinia 全局大状态 | token/user 用小模块或 composable 即可；真要 Pinia 只放 auth |
| 语言 | 当前为 JS | 已落地 JS 则保持统一；不要中途混 TS |
| 包管理 | npm（当前） | 与现有 `package.json` 一致即可 |
| 目录 | 仓库根 `teamdocs-frontend/` | 与 `teamdocs-backend/` 并列 |

不要上：Next.js、Nuxt（本阶段）、UI 换 Ant Design Vue、上微前端、上 monorepo 复杂工具。

## 2. 实现顺序与当前进度

| 序号 | 内容 | 状态（2026-07-25） |
|---|---|---|
| 1 | 脚手架 + 基础布局空壳 | 已完成 |
| 2 | 登录 / 注册 + Axios + token + 路由守卫 | 已完成 |
| 3 | 登录成功落地：「我的空间」**真列表** | **下一步（当前占位页）** |
| 4 | 空间内：文件夹 / 文档列表 / 上传下载 | 未做 |
| 5 | 标签 / 评论 / 最近浏览 | 未做 |
| 6 | 个人资料 / 改密 / 头像 | 后端已齐；前端 API 部分已挂，UI 可后置 |

先登录，后内页。不要先堆静态大盘。  
**当前优先：把 `/spaces` 从欢迎占位改成真实空间列表 + 创建 + 进入空间。**

## 3. 接口约定（必须遵守）

### 3.1 基址

- 开发代理：Vite `server.proxy['/api']` → `http://localhost:18080`（Docker 默认 `BACKEND_PORT=18080`）
- 请求 `baseURL` 用 `/api`，rewrite 去掉 `/api` 前缀后转发后端
- 本地若不用 Docker、后端直接 `8080`，改 `vite.config.js` 的 proxy target，不要在业务代码写死多套环境

### 3.2 统一响应

```json
{ "code": 1, "msg": "success", "data": ... }
```

- `code === 1`：成功，拦截器返回 `data`
- `code === 0`：业务失败，**用 `msg` 提示用户**（HTTP 仍可能是 200）
- HTTP 401：清 token，跳转登录（防抖，避免并发重复弹窗）

Axios 拦截器必须处理：业务 `code`、HTTP 401、网络错误。不要只判断 HTTP status。

### 3.3 认证

- 登录成功：`data` 为对象 **`{ token, user }`**（`LoginResultVO`）
  - 兼容写法可保留：`const token = typeof data === 'string' ? data : data?.token`
  - 推荐同时缓存 `user`（可选 localStorage key：`teamdocs_user`），减少首屏再拉
- 存储 token：`localStorage` key **`teamdocs_token`**
- 请求头：`Authorization: Bearer <token>`
- 无 token 不得进 `requiresAuth` 路由；有 token 访问 `/login` 应重定向到 `/spaces`
- 不要用 Cookie Session（后端无状态 JWT）

### 3.4 用户模块（后端已提供，按需联调）

| 操作 | 方法 | 路径 | 请求 | 成功 data |
|---|---|---|---|---|
| 注册 | POST | `/user/register` | `{ username, password }` | 无 / null |
| 登录 | POST | `/user/login` | `{ username, password }` | `{ token, user }` |
| 当前用户 | GET | `/user/info` | — | `UserProfileVO` |
| 更新资料 | PUT | `/user/profile` | `{ nickname?, email? }` | `UserProfileVO` |
| 修改密码 | PUT | `/user/password` | `{ oldPassword, newPassword }` | 无 |
| 上传头像 | POST | `/user/avatar` | `multipart/form-data` 字段名 **`file`** | `UserProfileVO` |
| 退出登录 | POST | `/user/logout` | Header 带 Bearer | 无 |
| 最近文档 | GET | `/user/recent-documents` | — | 列表（后置） |

**校验（前后端一致）**

- username：2–16（注册）
- password / newPassword：6–20
- nickname：最长 50；可传空串清空（后端 trim 后 blank → null）
- email：合法邮箱，最长 100；可传空串清空；唯一
- 头像：JPG/PNG/GIF/WEBP，业务上限 2MB；**FormData 不要手写 Content-Type**（交给浏览器带 boundary）
- 容器 multipart 已放宽到约 20MB（文档上传用）；头像仍以业务 2MB 为准

**登录 / 注册行为**

- 注册成功推荐：自动再调登录 → 存 token → 跳转 `/spaces`
- 登录/注册失败：展示后端 `msg`（如「用户名或密码错误」「请求过于频繁」「用户名已存在」）
- 登录响应无 token：必须提示错误，禁止静默失败
- 退出：先调 `POST /user/logout` 撤销服务端 token，再清本地并 `replace` 到登录页  
  - 服务端失败时：仍清本地并跳转；**不要再弹「退出成功」**（拦截器已报错）

**UserProfileVO 字段**

```text
userId, username, nickname, email, avatar, status, createdAt
```

注意字段名是 **`userId`**，不是 `id`。

### 3.5 空间模块（下一步主路径）

注意路径前缀是 **`/space`**（单数），不是 `/spaces`。

| 操作 | 方法 | 路径 | 请求 | 成功 data |
|---|---|---|---|---|
| 我的空间列表 | GET | `/space/list` | — | `Space[]` |
| 创建空间 | POST | `/space` | `{ name, description? }` | 无 |
| 空间详情 | GET | `/space/{id}` | — | `Space` |
| 更新空间 | PUT | `/space/{id}` | 见后端 DTO | 无 |
| 删除空间 | DELETE | `/space/{id}` | — | 无 |
| 成员相关 | `/space/{id}/members` | 后置 | | |

**CreateSpaceDTO**

- `name`：必填，1–64
- `description`：可选，最长 255

**Space 字段（列表/详情）**

```text
id, name, description, ownerId, deleted, createdAt, updatedAt
```

**第二期页面要求（空间列表）**

1. 进入 `/spaces` 调 `GET /space/list` 渲染真数据
2. 空态：引导「创建第一个空间」
3. 创建：弹窗/抽屉提交 `POST /space`，成功后刷新列表
4. 点击空间进入空间内页（路由建议 `/spaces/:spaceId`，第三期再做文件夹文档）
5. 顶栏保留用户名 + 退出登录（已实现逻辑可复用）

### 3.5.1 UI 观感约束（去「演示稿 / AI 模板」味，硬约束）

目标：像**轻量 B 端工作台**，不像居中欢迎页或组件库 demo。

**通用**

1. 内容区**左对齐 + 定最大宽度**（建议约 1120–1200px 居中容器），主内容**不要垂直居中成一张 hero 卡**
2. 主界面**禁止展示**开发向字段：`spaceId`、`ownerId`、创建者 ID、内部状态码等（调试可放 dev-only，默认用户看不到）
3. **禁止**用户可见的期次/roadmap 文案（如「第二期就绪」「第三期将拓展…」）
4. 少用大块圆角渐变色图标底；图标线框/中性即可，主色只留给主按钮
5. 少装饰悬浮球、营销徽章；层级靠字重与灰阶，不靠一堆彩色 chip
6. 不要为去 AI 味换插画、玻璃拟态、赛博风；在现有 Element Plus 上收敛即可

**空间列表 `/spaces`**

1. 标题行：左「我的空间」+ 弱化计数（纯文字或轻 tag），右「新建空间」
2. 卡片网格 2–3 列，有数据时铺满内容区，避免单卡漂在页面中央
3. 卡片字段仅：`name`、一行 `description`（空则「暂无描述」）、`createdAt`（日期即可）
4. **整卡可点**进入空间；去掉卡片内「进入空间 >」类重复入口文案
5. 空态一句引导 + 主按钮即可，不要长说明

**空间内页壳 `/spaces/:spaceId`**

1. 顶栏：返回列表 + 空间名；描述最多副标题一行
2. 主区用**工作区骨架**（左文件夹占位 / 右文件列表占位，或上下工具条+列表区），不要再居中一张大信息卡
3. 空内容文案用产品口吻：「还没有文件，上传后会出现在这里」（第三期再真做上传）
4. 删除元数据展示条（空间 ID / 创建者 ID / 「内页壳就绪」提示）

### 3.6 文件夹 / 文档（第三期再全面联调，先锁路径）

路径前缀是 **`/spaces/{spaceId}`**（复数），与空间模块单数 `/space` 不同，**不要写错**。

| 操作 | 方法 | 路径 | 备注 |
|---|---|---|---|
| 子文件夹列表 | GET | `/spaces/{spaceId}/folders?parentId=0` | 根目录 `parentId=0` |
| 创建文件夹 | POST | `/spaces/{spaceId}/folders` | body 见后端 |
| 重命名文件夹 | PUT | `/spaces/{spaceId}/folders/{folderId}` | |
| 删除文件夹 | DELETE | `/spaces/{spaceId}/folders/{folderId}` | |
| 移动文件夹 | PUT | `/spaces/{spaceId}/folders/{folderId}/move` | |
| 文档列表 | GET | `/spaces/{spaceId}/documents?folderId=0` | 分页 `PageQuery` |
| 上传文档 | POST | `/spaces/{spaceId}/documents/upload` | `file` + `folderId`（默认 0） |
| 下载 | GET | `/spaces/{spaceId}/documents/{documentId}/download` | data 为预签名 URL 字符串 |
| 重命名/移动/删除/回收站/恢复/彻底删除/搜索 | 见后端 DocumentController | 第三期 |

`folderId = 0` 表示根目录。跨空间/不存在的 folder 后端会拒。

### 3.7 明确禁止实现（本阶段）

- 第三方登录（Google / GitHub / 微信）
- 手机号、验证码、邮箱登录
- 忘记密码 / 重置密码完整流程
- 邮件收发
- 强制完善邮箱资料（资料页可后置，且非阻塞）
- 自创假后端 / mock 顶替真接口（开发期 UI 骨架可以，联调前必须换真 API）

## 4. 登录页产品决策

| 决策 | 结论 |
|---|---|
| 布局 | 当前居中卡片已可接受；分栏亦可，勿大改栈 |
| 字段 | 仅账号、密码 |
| 模式 | 登录 / 注册可切换（同页） |
| 文案 | 中文优先；产品名 `TeamDocs` |
| 动效 | 轻动效可保留；表单区安静 |
| 视觉 | 简约 B 端；不要 C 端社交/赛博/游戏风 |
| 注册后 | 进空间列表；资料完善非阻塞 |

设计 brief：`docs/design/login-page.md`（只锁边界，不锁像素）。

## 5. 工程约定

1. 路径别名 `@/` → `src/`
2. API 按模块：`src/api/user.js`、`src/api/space.js`（第二期新增）等
3. 路由 meta：`requiresAuth: true/false`
4. 已登录访问登录页 → 重定向到空间列表
5. 未登录访问业务页 → 登录页
6. 不要把 token 打进 console / 错误上报明文
7. 列表空态、按钮 loading、表单校验错误要有
8. 中文 UI，Element Plus 用 zh-cn
9. 上传类接口：传 `FormData` 时**不要**手动设置 `Content-Type: multipart/form-data`
10. 业务失败提示以拦截器 + 后端 `msg` 为准，避免页面再弹一遍相同错误（除非需要更具体的上下文）

## 6. 验收标准

### 6.1 第一期（登录，已基本达成）

1. 能注册新用户并登录拿到 token
2. 错误密码显示后端 `msg`
3. 限流文案能展示（若触发）
4. 刷新页面仍保持登录（localStorage）
5. 无 token 不能进业务路由
6. 退出会通知后端撤销 token，并清本地
7. 无第三方登录/忘记密码假入口

### 6.2 第二期（空间列表，下一步）

1. `/spaces` 展示 `GET /space/list` 真数据
2. 可创建空间并刷新列表
3. 空态可用
4. 可进入某个空间路由（内页可先简单壳）
5. 退出登录行为符合 3.4

### 6.3 第三期（空间内文档，再后）

1. 文件夹浏览 + 文档列表
2. 上传 / 下载主路径可演示
3. 基础重命名/删除可后置完善

## 7. Claude 审查清单

只报问题：

1. 是否违反接口边界（多字段、假 OAuth、假邮件、路径写错 `/space` vs `/spaces`）
2. 是否错误处理 `code===0` vs HTTP 状态
3. token 存取、401、logout 是否正确
4. 路由守卫是否漏洞
5. 是否引入未批准依赖 / 过度架构
6. 是否有明显 XSS（`v-html` 乱用）、把密钥写进前端
7. 上传是否误设 multipart Content-Type
8. 主路径是否可演示

不要求：像素级设计还原、完整 E2E、生产级监控、i18n 多语言。

## 8. 给 Gemini 的下一期开工指令（可复制）

```text
在 F:/CodeProject/TeamDocs/teamdocs-frontend 继续开发。
严格遵循 docs/frontend/DECISIONS.md（已更新）。

当前已完成：登录/注册、Axios、token 守卫、退出调后端 logout。
空间页仍是占位欢迎卡，需要改成真业务。

第二期只做：
1) src/api/space.js：listMySpaces、createSpace（路径 /space/list、POST /space）
2) 改造 SpaceListView：请求真列表、空态、创建空间、点击进入 /spaces/:spaceId
3) 新增空间内页路由壳（可为简单占位，显示 spaceId + 返回列表）；文件夹文档第三期再做

不要做：第三方登录、忘记密码、手机号、标签评论大盘、强行完善资料。
不要改技术栈。上传相关若碰到 FormData，不要手写 Content-Type。
后端代理保持 http://localhost:18080。统一响应 {code,msg,data}，code=1 成功。
登录 data 为 { token, user }；用户字段 userId 不是 id。
```

## 9. 变更流程

- Gemini 若要改栈、加依赖、加假接口：先停，等 Claude/用户确认
- Claude 改决策时只更新本文件，并简短告知用户
- 设计稿可放 `docs/design/mockups/`，不阻塞编码
- **本 `docs/` 目录按用户要求可不随代码仓提交**；以文件内容为契约，不以 git 是否跟踪为准
