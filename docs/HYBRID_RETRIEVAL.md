# 混合检索与重排

## 范围与当前验证边界

TeamDocs 在原有 ES/MySQL 关键词检索上增加文本向量召回和候选重排，不更换聊天模型、不引入 JEV，也不注册默认或兜底模型供应商。

当前实现使用 Milvus REST v2 与已有 OkHttp，不增加 Milvus Java SDK，不升级 LangChain4j。Docker 镜像固定为 Milvus 3.0.2。已通过独立容器验证真实 Milvus 协议，并用合成文字验证实际 Embedding/Reranker 接口；没有操作业务数据库。这些小样本验证不代替完整部署和真实语料的召回质量验收。

## 配置与启用

配置项位于 `.env.example`。真实 `.env` 不提交；空 Key、`YOUR_*`、`replace-me` 等占位值不触发外部请求。填写有效 Key 后，对应功能自动开启，不必再逐项设置 `*_ENABLED=true`。

1. Agent 使用 `AGENT_BASE_URL`、`AGENT_API_KEY`、`AGENT_MODEL_NAME`。
2. MCP 使用 `MCP_STEP_URL`、`MCP_STEP_API_KEY`，配置后发现工具。
3. Embedding 使用 `EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY`、`EMBEDDING_MODEL`、`EMBEDDING_DIMENSIONS`。
4. Reranker 使用 `RERANK_BASE_URL`、`RERANK_API_KEY`、`RERANK_MODEL`。
5. Milvus 使用 `MILVUS_URL`、`MILVUS_TOKEN`、`MILVUS_COLLECTION`。本地未启用 Milvus 认证时 Token 可以为空。

示例明确填写硅基流动地址与 `Qwen/Qwen3-Embedding-8B`、`Qwen/Qwen3-Reranker-8B`，应用不在缺少配置时自动选择该供应商。两个 API Key 可以填同一账户的 Key，但没有配置继承或跨模型兜底。

**填写真实 Embedding Key 会允许后台向配置的服务发送已有 READY 文档正文，而不只是当前问题。** Reranker 会接收查询和最多二十个候选片段；MCP 会接收工具参数。配置者必须先确认对应资料的出站范围、供应商政策和账户费用。初次验证只使用隔离的合成资料。

需要临时完全关闭时可移除对应 Key；测试或运维也可通过 Spring 属性显式覆盖，例如 `--teamdocs.embedding.enabled=false`。运行中更改 `.env` 不会自动修改已经启动的 JVM 配置，需要按实际启动方式重新加载。

## 本机 Docker

保留根目录 `docker-compose.yaml`。新增三个服务集中在 `semantic` profile：`milvus`、`milvus-etcd`、`milvus-storage`。后两者不发布宿主机端口，也不复用业务 MinIO 的数据卷。仅 Milvus 的 API 与健康检查端口绑定回环地址。

先释放本机内存并检查端口、镜像和存储空间，再执行以下命令；它只启动 Milvus 及其两个依赖，不启动或替换现有业务服务：

```sh
docker compose --profile semantic up -d --wait milvus
```

现有 Compose 的数据库、Redis、MinIO 必填参数也需要在 `.env` 中配置，Compose 会解析整个文件。`MILVUS_MEMORY_LIMIT=4g` 是开发配置的容器上限，不是容量承诺。三个镜像已成功拉取；隔离协议测试使用 2 GiB 上限的 Milvus、256 MiB 的 etcd 和 384 MiB 的专用 MinIO，仅证明少量合成向量可运行，没有放宽 Docker 默认 seccomp。完整 Compose 尚未启动验收。

`MILVUS_STORAGE_USER` / `MILVUS_STORAGE_PASSWORD` 是向量专用 MinIO 凭据，不是 Milvus API Token。内置密码回退仅用于回环开发环境，长期使用前填写独立强密码；本地 Milvus 默认未开启身份认证，不得将 19530/9091 或容器网络暴露给不受信任的用户。

原生 IDEA 后端使用 `MILVUS_URL=http://127.0.0.1:19530`；完整 Compose 的后端环境覆盖为容器内 `http://milvus:19530`。不要把容器内地址填到原生进程配置中。普通 `docker compose up` 不会默认启动 semantic profile。

## 数据与生命周期

MySQL 新增 `document_vector_task`。Compose 空库初始化只挂载 `sql/init.sql`，按用户、空间、文档、评论、日志、全文索引、正文、Agent、向量待办的顺序执行；没有生成脚本。原分项 SQL 保留给已有测试与针对性初始化使用，部署入口以 `init.sql` 为准。已有数据库只应在备份、审核后单独补充 `sql/vector_index.sql` 中的新增表；不要重跑整套含 DROP 的初始化脚本。本次未执行任何业务库 SQL。

这张表只保存文档 ID、目标签名、任务代次、状态、次数和重试时间，不保存正文，也不是第二套文档解析状态。文档解析继续以 `document.parseStatus/parseVersion` 为准。

1. 原有文档索引同步入口登记向量待办，存在业务事务时参与该事务；登记失败不掩盖原业务结果，由补扫修复。
2. 独立单线程调度器每次处理一个文档，向量化每批最多十六块，单任务最多 120 秒。
3. 每次外部尝试前先持久化次数，最多三次；HTTP 客户端不做隐藏重试。进程中断后尝试次数保留，但不能承诺已发出的向量化请求不会重复计费。
4. 目标签名包含模型、维度、Milvus 地址、Collection 与解析状态。目标变化增加代次，旧任务不能完成或清除新待办。
5. 软删除、彻底删除、重新解析、恢复会产生清理或重建任务。索引可能短暂滞后，返回前仍必须经过 MySQL 复核。
6. 每三十秒有界扫描一百个文档，修复漏事件并为初次启用补齐索引；已有相同目标的 DONE/FAILED 不会因补扫反复调用模型。

`FAILED` 保留脱敏错误码。排除配置或服务故障后，可由维护者只重置指定文档的向量待办（`state=PENDING, attempts=0, next_attempt_ms=0`），不要重置整个业务库。当前只支持单后端进程，不提供分布式 Worker 租约。

Milvus Collection 只保存 `chunk_id`、`document_id`、`space_id`、`parse_version` 和 `embedding`，没有正文。使用 COSINE 与 FLAT 作为小规模基线。默认示例维度为 1024；调整模型或维度时使用新的专用 Collection。已有不兼容 Collection 会被拒绝，不自动删除重建。相同模型在供应商侧发生不可见升级时，维护者也应更换 Collection 并重新评测。

## 查询过程

1. ES 和 Milvus 分别召回最多二十个候选，目前在同一受限请求中顺序执行，不额外创建并行线程池。
2. 关键词路径不足时用 MySQL 补齐；向量化使用未经 MySQL Boolean 查询语法改写的原始查询。
3. 索引搜索提前限定空间和可选文档；每个候选回 MySQL 校验当前版本、READY、删除状态与空间。
4. RRF 合并排名并去重，最多二十个候选交给 Reranker，不直接混加不同引擎的原始分数。
5. Reranker 只返回输入索引，由本地原始候选映射结果；返回前再次校验，最终最多六条，仍遵守正文长度上限。
6. 外部故障回退到已有候选或关键词路径，不换供应商。权限、取消、截止时间异常不作为普通降级吞掉。

所有发送到重排接口的候选都登记为 Agent 来源依赖，即使没有进入最终回答；历史答案仍遵守来源变化后整体遮蔽的规则。重排分数不是事实正确率或授权依据，也没有未经评测的固定拒答阈值。

## 验证

无需模型 Key、不启动 Docker 的专项测试：

```sh
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=SiliconFlowClientsTest,MilvusVectorClientTest,HybridChunkSearchTest,VectorIndexWorkerTest,RetrievalContextTest,RetrievalActivationTest' test
```

可选真实 MySQL 待办测试（显式开启后会启动独立 Testcontainers MySQL，默认不运行）：

```sh
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=VectorIndexSqlTest' '-Dteamdocs.test.vector-sql=true' test
```

本轮离线后端回归共 220 项：216 通过，4 项需显式启动 MySQL 的待办测试当时跳过，无失败或错误；排除了其他原有 Docker/在线模型集成测试。2026-10-01 已复跑并完成后端 JAR 打包，日志为 `teamdocs-backend/target/hybrid-resume-verification.log`。

Docker 就绪后追加运行真实 MySQL 验证：`DatabaseInitializationTest` 1 项、`VectorIndexSqlTest` 4 项全部通过，日志为 `teamdocs-backend/target/hybrid-sql-integration.log`。这验证了合并入口、事务回滚、任务代次和有限重试，不是 Milvus 或真实模型验收。临时测试容器已清理，未操作业务库。基础与 semantic profile 的 Compose 静态校验以及更新后的 3 项部署配置测试通过。

CI 普通测试一次执行并包含向量 SQL 测试；固定检索评估只在手动触发时勾选 `run_retrieval_evaluation` 运行，不额外重复普通测试集合，也不在评估模式发布镜像。

2026-10-01 追加实际验证：

1. `SemanticLiveApiTest` 两项通过：真实批量向量化返回正确维度，回滚合成资料的相似度高于无关资料；真实重排将相关合成资料排在第一。只发送固定合成文本，不读取业务文档，不打印 Key。日志 `target/semantic-live-api.log`。
2. `MilvusContainerIntegrationTest` 一项通过：真实集合创建、写入、空间/文档过滤、版本 0 与删除协议。三个临时容器均已清理，业务容器未停止，镜像仍缓存在本机。日志 `target/milvus-real-integration.log`。

这两类测试默认不运行。再次运行需要显式授权：

```sh
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=SemanticLiveApiTest' '-Dteamdocs.live-semantic=true' test
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=MilvusContainerIntegrationTest' '-Dteamdocs.test.milvus=true' test
```

第一条会调用配置的模型并可能计费；第二条只使用合成向量，但会启动隔离 Docker 容器。完整 Compose、上传到问答的应用全链路和真实语料效果验收仍未完成。效果评测保持现有固定集不变，另增同义改写题，对比关键词、向量、混合、混合加重排四条路径，同时报告 Top-K 证据命中与延迟。小样本接口验证不等于提升了整体召回率或回答正确率。
