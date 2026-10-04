# P4：文档问答页面与 SSE

## 使用与边界

空间工作台和展开的空间侧栏均有「文档问答」入口，路由为 `/spaces/:spaceId/agent/:sessionId?`。会话固定属于当前用户和空间；切换空间、新对话或离开页面只停止观察，不等于取消后台任务。需要取消时点击「停止」。

页面提供会话历史、分页、提问/追问、工具名称/结果条数/耗时、终态和来源卡片。来源打开既有文档预览，只显示真实的片段编号/页码，不伪造精确定位。回答使用经过清洗的 Markdown 展示，禁止原始 HTML。Enter 发送，Shift+Enter 换行，输入法确认不会发送。

模型和出站授权仍按 [后端配置](AGENT_BACKEND.md) 执行。页面可见不表示管理员已授权真实资料出站。不会新增写工具、自动调用真实模型或修改业务数据库。

## 文件级引用与实时处理摘要

- 同一条回答内按文档及解析版本统一显示 `[1]`、`[2]`。同一段落中引用同一文件的多个片段只显示一次编号，底部每个文件一张来源卡。
- 点击段落编号，先通过已有运行查询接口重新校验该段所有引用，再展示其真实摘录、页码和片段位置；点击“打开文档”沿用已有预览。后台的 C1/C2 等原始编号和记录不变。
- Markdown 代码、链接和转义示例不转换成来源按钮；未知编号不生成可点击来源。切换会话、权限失效或关闭弹层后，迟到响应不能重新显示旧摘录。
- 助手回复区根据 SSE 快照显示当前处理摘要，例如“等待模型响应”“正在检索相关内容”“正在阅读文档内容”。运行时处理记录默认展开，完成后收起；次数和耗时只在额外展开“技术详情”后显示；问候完成后不保留空检索面板。
- 目前后端通过同步模型接口取得完整响应，没有接入模型文本增量。首个响应返回前只能显示等待状态，不会伪造“思考过程”；工具开始与完成后展示真实执行记录。最终答案仍在来源校验后一次性发布。

## 流协议与安全

- `GET /api/spaces/{spaceId}/agent/runs/{runId}/events`（直接访问后端时去掉 `/api`），使用 fetch 流和 `Authorization: Bearer …`，不把凭证放在 URL。
- 事件有 `snapshot`、`run_started`、`model_started`、`tool_started`、`tool_finished`、`answer_ready`、`run_finished`、`run_failed`、`stream_error`。每帧包含 `spaceId`、`sessionId`、`runId`、`sequence`、`snapshot` 和 `errorCode`。序号仅在当前连接中递增，重连后重置。
- 发送的是**已提交且重新授权的完整运行快照**，不是模型原始 token 或思维链。通知只触发读取；不缓存私有答案用于重放。初始快照与完成通知的竞争有集成测试覆盖。
- 每次加载检查当前空间成员与运行归属；引用失效时遮蔽整个衍生回答。失权发出 `ACCESS_REVOKED` 并关闭流；前端清空回答、会话标题及待提交内容，作废未完成的列表请求。401 清除 token 并跳转登录。
- 每进程最多 32 条连接，每用户最多 2 条，每订阅最多 32 个待处理通知；发送线程和队列有界。10 秒刷新权限与状态，单连接 120 秒到期，由客户端恢复观察。终态发送后关闭连接。
- 断线先 GET 当前运行；若仍运行则退避重连，最多 5 次，之后显示手动重连入口。恢复观察不 POST 新运行。提交结果不确定时保留同一个 `clientRequestId` 供重试。
- 前端同时校验空间/会话/运行、上下文代次、观察代次和事件顺序；切换后丢弃旧 HTTP 响应和旧事件。取消后迟到结果不能覆盖取消终态。
- 当前仅支持单后端进程，与 P3 的运行恢复边界一致；不提供分布式事件总线。

## 代理与本地运行

生产 `teamdocs-frontend/nginx.conf` 的 `/api/` 代理保留 Bearer 请求头并关闭响应缓冲和 gzip，清空上游 Connection 头，读超时 310 秒。后端返回 `Cache-Control: no-store` 和 `X-Accel-Buffering: no`。如果另有外层反向代理，也必须允许流式刷新且不能缓存事件响应。

Vite 默认代理 `http://localhost:8080`；隔离联调可设置 `TEAMDOCS_API_TARGET` 为测试后端地址，不能把它误设到业务服务。

```powershell
npm --prefix teamdocs-frontend test
npm --prefix teamdocs-frontend run build
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=AgentSseIntegrationTest' test
```

前端测试需要 Node.js 22.15+（CI 使用 Node.js 22），通过 Node 模块钩子替换 API，运行真实 Vue 状态组合函数，不新增测试框架依赖。后端测试需要 Docker；使用隔离 MySQL/Redis/MinIO 和真实 Nginx，模型为 Mock。

### 可选浏览器夹具

```powershell
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=AgentSseIntegrationTest#browserFixture' '-Dteamdocs.browser-fixture=true' test
```

等待 `P4_BROWSER_FIXTURE_READY` 后，读取 `teamdocs-backend/target/p4-browser-fixture.json` 中的测试地址和临时登录信息。将 `TEAMDOCS_API_TARGET` 指向该地址，再启动 Vite。夹具最多运行 15 分钟；创建其中 `stopFile` 指定的文件可提前结束。信息只用于隔离库，不应复制到部署配置或提交到 Git。默认 CI 跳过该夹具，它不是自动化浏览器测试。

## 2026-09-29 验收结果

- 后端全量：204 项，200 通过、0 失败/错误、4 跳过（3 项真实模型测试未启用，1 项浏览器夹具另行手动运行）。6 项 SSE 集成测试全部通过，包括真实 Nginx 后的渐进推送。本轮没有重复调用真实模型。
- 前端：14 项全部通过。新增 8 项覆盖旧响应/旧事件丢弃、创建会话期间切空间、同键重试、断线不重提、取消和权限撤销清理；权限撤销的两项先复现失败，修复后通过。
- gstack `/browse` 隔离联调：登录 → 上传合成 TXT → 后台解析 → 新建问答 → 工具过程 → 带来源回答 → 来源预览正文 → 同会话追问通过；停止和运行中切空间通过；无资料空间显示未检索到正文，不引用其他空间内容。
- 浏览器人为切断第一条 SSE 响应后，观察到 2 次 SSE GET、1 次快照 GET、仅 1 次运行 POST，最终回答正常显示。无效测试 token 导致 401、token 清除和登录跳转。
- 1280×720 桌面和 390×844 手机布局已截图检查；手机页面无横向溢出。来源预览显示完整合成正文。
- 最终前端构建、后端 JAR 打包、Compose 配置和 `git diff --check` 均通过；测试后端、Vite 与浏览器已停止，Testcontainers 容器已清理。前端构建保留既有预览依赖的大 chunk 警告；工作台上传时仍有既有 Element Plus 非元素根节点 directive 警告，未在 P4 中扩展修改。
- 本地证据保存在已忽略的 `teamdocs-backend/target/p4-*` 日志和截图中。不代表生产部署验收、模型回答质量评测或并发性能承诺；后续质量与交付归 P5。
