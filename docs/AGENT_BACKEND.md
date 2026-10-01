# P3：只读文档 Agent 后端

## 范围

保留 Java 17 / Spring Boot 3.5 / LangChain4j 0.36.2，直接使用低层模型消息与工具协议。模型决定检索/读取顺序，应用执行显式循环；没有 AI Services 自动工具执行、向量数据库、SSE、聊天页面或写工具。

当前三个工具：

- `search_documents(keyword, page?)`：当前空间的文档名/标签搜索，只提供脱敏元数据，最多 6 条。
- `search_document_chunks(keyword, documentId?)`：正文检索；优先 ES，并按当前空间、文档、解析状态与版本复核。不足时走 MySQL；指定文档时 SQL 在排序和 LIMIT 前限定文档。
- `read_document_chunks(documentId, startIndex?, limit?)`：有界顺序读取，返回 `chunkIndex`、`hasMore` 和解析状态；继续读取时使用末块的 `chunkIndex + 1`。

工具不能接受 userId、spaceId 或 sessionId。它们来自 JWT 与服务端会话，异步线程显式携带 LoginUser，逐次通过 Spring 代理执行空间权限检查。参数拒绝未知字段、重复 JSON 键、尾随内容、错误类型和越界数值。

## 初始化与开关

本阶段增加 `sql/agent.sql`，由 Compose 空库初始化按第八份脚本执行，新增：

- `agent_session`、`agent_run`、`agent_message`：会话、幂等请求、生命周期和最终消息。
- `agent_tool_call`：工具名、参数长度、结果条数、耗时和错误码，不保存原始参数/正文。
- `agent_model_call`：每次付费尝试的预占凭证、配置报价、实际 token 用量和已占额度。它不是第二套文档解析状态。

交付仍面向全新服务器/空数据库，没有增加版本化迁移或修改已有开发数据库。普通启动依赖上述表已初始化；初始化缺失时 AI 恢复失败并拒绝新运行，不会自动创建/删除业务数据。

继续使用原来的模型地址、密钥与名称；额外配置：

```dotenv
AGENT_ENABLED=true
AGENT_ALLOW_DOCUMENT_EGRESS=false
AGENT_INPUT_PRICE_PER_MILLION=
AGENT_OUTPUT_PRICE_PER_MILLION=
AGENT_DAILY_BUDGET=
```

价格和日额度不提供猜测的默认值。按供应商报价填入**同一币种**的每百万输入/输出 token 价格及每用户日额度。零单价只适用于确认免费的模型；日额度必须大于零。缺失配置会拒绝 AI 请求。

**设置 `AGENT_ALLOW_DOCUMENT_EGRESS=true` 前，必须向使用者说明并取得授权：**用户问题、仍可访问的少量历史、必要文档元数据和正文片段会发往配置的模型供应商；应核对其数据保留/训练政策。不会默认上传原文件或整个库，已发送的数据无法承诺收回。本次在线验收仅使用隔离测试库的无敏感样例，不代表已授权发送真实空间内容。

## 执行和费用边界

默认每用户同时 1 个运行（跨会话也限制）；运行最多 6 次模型尝试、8 次工具尝试、90 秒。排队也计时，截止时间使用 epoch 毫秒，SQL 的当前时间由应用统一传入，避免混用 JVM/MySQL 时区或不同主机时钟。工作队列最多 8 项；执行线程和模型线程各 2 个，模型队列不堆积。请求/响应有界，SDK 隐式重试关闭。

完整消息（包括历史、工具定义、调用参数、工具结果）序列化后用 UTF-8 字节数加协议余量保守估计输入 token，默认上限 16000；模型最大输出 1024 token。历史最多 3 轮并受输入预算约束；不做自动长期摘要。一次来源/格式修正也计入模型次数、上下文和费用。设置只能降低/有界调整执行限制，最大 6/8/90 秒与输出 4096 token 有硬上限。

每次外部模型尝试前，在 MySQL 事务内锁定用户行，按完整输入和最大输出原子预占当日额度，再增加调用计数。相同用户的并发预占不会越过额度。返回有效用量后按该次报价结算；超时、异常、断流、重启或缺少用量时保留预占，不假装免费、不自动重放。供应商违反输入/输出用量边界时记录已知用量并停止后续调用。

`chargedCost` 是项目侧额度占用，不是供应商账单：其中可能包含未知用量的保守预占；`usageUnknown` 明确标识。缓存折扣、特殊计费或隐藏 token 等情况应使用保守报价，并验证供应商遵守最大输出参数，不能用不完整的价格承诺真实账单绝不超额。

取消立即落库，后续工具/模型调用及最终发布均检查运行状态。底层 HTTP/JDBC 调用未必能瞬间停止，已付费用不可撤销；有界线程池和超时避免无限堆积，迟到结果不能复活终态。SDK 单次超时最大 90 秒，通常应设得更短。

**部署为单个后端进程。**启动时把无法恢复的 QUEUED/RUNNING 运行标记为 PROCESS_INTERRUPTED，不重放收费请求，也不退回未知额度。P2.5 一次性索引维护进程不会执行这项恢复。多实例租约和自动接管不在本阶段范围内。

## 引用、历史与结果遮蔽

- 后端只为实际读取的正文分配本轮 `C1`、`C2` 等编号。模型最终返回 `answer/citations` JSON；编号必须真实存在，且与正文中的 `[C1]` 标记一致。
- 最多允许一次格式/引用修正，仍失败则不展示原始答案。没有检索到资料时明确不足，不能编造引用。
- 保存的依赖包括所有发送给模型的文档（包括未引用的元数据/正文）以及历史回答的传递依赖。不保存模型私有思维链或完整 Prompt。
- 文档删除、失权、版本/状态/名称/修改时间变化后，整个衍生答案被遮蔽；引用也清空。历史加载时跳过这整轮问答，不再把旧答案送给模型。后续回答继承它实际使用的历史依赖。
- 引用链接由后端构造为站内 `/preview/{spaceId}/{documentId}`；不会使用模型提供的 URL，也不伪造精确页码跳转。
- API 的 `text` 是**不可信纯文本**。前端应使用文本插值，不使用 `v-html`；如 P4 增加 Markdown，必须禁用原始 HTML 并限制链接协议。引用按钮只取 `citations` 中的后端对象。
- 来源合法不代表结论一定被证据支持；事实一致性和更大样例评测留在后续质量阶段。

## REST 接口

统一前缀 `/spaces/{spaceId}/agent`，均要求 Bearer Token、当前空间成员资格和本人会话/运行。沿用 `{code,msg,data}` 响应。

| 方法 | 路径 | 请求 / 返回 |
|---|---|---|
| POST | `/sessions` | `{"title":"上线资料问答"}`，返回会话 |
| GET | `/sessions` | `current` / `size` 分页 |
| GET | `/sessions/{sessionId}/messages` | 分页返回本人消息及遮蔽状态 |
| POST | `/sessions/{sessionId}/runs` | `{"clientRequestId":"uuid-or-client-key","question":"上线前如何备份？"}`，立即返回 `runId` |
| GET | `/runs/{runId}` | 状态、错误码、次数、用量/占用、脱敏工具轨迹和已校验答案 |
| POST | `/runs/{runId}/cancel` | 取消后续执行，返回当前快照 |

问题最多 2000 字符；请求键最多 64 个 ASCII 字母/数字/横线/下划线。相同会话内同键同问题（去首尾空白后）返回原运行，同键不同问题拒绝。分页最大每页 100 条、页码 10000。`deadlineMs` 可直接用于客户端倒计时。SSE `/events` 尚未实现，客户端应轮询快照，不因断线自动重复提交。

运行终态：SUCCEEDED、FAILED、CANCELLED、TIMED_OUT。常见错误码包括 AI_BUDGET_NOT_CONFIGURED、DAILY_BUDGET_EXCEEDED、MODEL_CALL_LIMIT、TOOL_CALL_LIMIT、CONTEXT_LIMIT、SOURCE_CHANGED、ACCESS_REVOKED、ANSWER_UNVERIFIABLE、RUN_TIMEOUT、PROCESS_INTERRUPTED。预算耗尽时可以返回确定性的未完成说明和仍有效的来源，不拼接未经校验的模型输出。

## 验证命令

常规测试不访问外部模型：

```sh
mvn -B -ntp -f teamdocs-backend/pom.xml -Dtest=AgentIntegrationTest,DatabaseInitializationTest test
```

测试会创建独立 MySQL 容器，使用合成文档验证真实 SQL、事务、幂等竞争、原子预占、配额故障、取消、超时、工具越权、来源修正、删除/失权遮蔽和传递依赖。

显式授权并配置模型后，可以只执行真实 Agent 验收：

```sh
mvn -B -ntp -f teamdocs-backend/pom.xml \
  '-Dtest=AgentIntegrationTest#liveModelReadsOnlySyntheticFixtures' \
  -Dteamdocs.live-agent=true test
```

该测试仅发送合成资料，分别进行单文档问答和多文档比较，共两次有界运行；测试价格是额度逻辑的样例值，不代表真实供应商报价。普通 CI 不开启此开关。


## 2026-09-28 验收

- 使用 `-Dteamdocs.live-agent=true -Dteamdocs.live-model=true` 显式开启授权测试，后端全量 `package`：197 项全部通过，无失败、错误或跳过，JAR 打包成功。
- 真实模型验证单文档问答和多文档比较；模型能够根据元数据结果继续选择正文读取工具，并给出有效来源与用量。两次运行都遵守单次执行预算。
- 用独立 MySQL 验证新表、请求唯一键、用户并发、费用原子预占、配额存储故障、取消、运行/排队超时、重启中断、工具参数/空间边界、引用修正和传递依赖遮蔽。验证中统一了应用与 SQL 的截止时间钟源。
- Agent 控制器的请求校验不会在日志中输出被拒绝的私有问题；正常响应沿用 Result 格式。
- 前端构建、Compose 配置校验通过；打包应用装配与新增接口未认证 401 检查通过。启动检查使用未使用的 loopback 依赖地址，仅验证装配/认证，不代表依赖健康检查通过。
- 真实模型验收期间曾出现 I/O 超时；增加只记录异常类型、次数和用量的诊断后重试通过。没有放宽来源或权限断言，不能据此保证外部供应商持续可用。
- 没有推送、部署或改动用户业务数据库。真实空间仍须先配置价格/日额度并确认文档出站授权；P4 的 SSE 和前端尚未实现。
