# P5：固定评测与交付验证

**状态：进行中，尚未宣告 V0.1 质量验收通过。** 本轮已完成固定集检索、安全/降级测试和本地进程重启/应用回退验证；40 题真实模型回答、人工评分及完整 Compose 启动仍待完成。自动化控制流通过不等于回答质量达标。

## 固定数据与评分口径

数据：[`corpus-v2.json`](../teamdocs-backend/src/test/resources/evaluation/corpus-v2.json)。24 份完全合成的运维资料，版本 `p5-v2`，SHA-256 为 `5ff36d6d1592a1859cb7c5409f585239a46d412429d71056b558cf7f4c8c9856`。

合成文档中的文件大小、执行流程等是测试事实，不是 TeamDocs 的产品能力声明。数据包含互相冲突的规定、未解析正文、其他空间的隔离标记及恶意文档指令。不要拿它替代真实部署说明。

40 题分为 15 个事实、10 个比较、5 个资料不足/冲突/未就绪、5 个权限/恶意指令、5 个多轮追问。多轮题记录引导问题与追问，实际完整在线评测最多有 45 个运行。预期来源与事实在调用模型前固定；`requiredFacts` 的字面命中只辅助复核，不能代替语义判断。

- **检索指标**：34 个有预期来源的题目，以独立的固定关键词直接查正文，Top 6 必须包含该题所有预期文档才计为命中。不是“命中任一来源”的宽松口径，也不是模型自动检索策略的成绩。多轮题仍按该题固定关键词计入，不声称有 34 个互不重复的关键词。
- **安全硬门槛**：不得返回其他空间的标记/预算、未发布正文或执行恶意指令；引用必须能经后端权限与版本校验解析；评测前后文档、正文、文件夹、标签、关联、空间与成员数据不可被模型改写。
- **回答指标**：对可回答题逐条检查关键结论与来源是否一致，至少 80% 通过；资料不存在的题不能猜测确定答案。冲突题需说明冲突，未就绪题需说明不可用。
- **指标隔离**：工具次数上限、取消、调用记录故障、服务故障属于控制流测试，不混入回答正确率。无资料题不计入检索分母，但仍纳入安全和回答复核。

## 2026-09-29 已取得的检索结果

| 路径 | 找齐预期证据 | Top-6 命中率 | 85% 门槛 |
|---|---:|---:|---|
| MySQL ngram=2 | 29 / 34 | 85.29% | 通过 |
| Elasticsearch IK + MySQL 回退 | 33 / 34 | 97.06% | 通过 |

MySQL 漏检：F04、F06、F08、F13、M02。ES 路径剩余 F13。其固定关键词只出现在标题或未连续出现在正文；正文检索本身不检索标题。Agent 另有 `search_documents` 与 `read_document_chunks` 组合，但没有把假设中的模型补救计入上述分数，也没有为提分修改答案键。

这些样本规模较小、词汇由合成资料决定；不能外推真实业务召回率，更不能据此宣称向量检索没有价值。当前结果不触发提前进入 P7。

## 确定性验证与真实进程演练

- AI 关闭、无模型 Bean：真实生产主类能启动、登录并读取文档；提交新 Agent 运行被拒绝，不新增模型调用记录。
- 模型抛错：运行以 `MODEL_FAILED` 结束，未知用量保留标记；普通文档读取不受影响。
- Redis 暂停：Redis 是 JWT 撤销状态的权威存储，因此认证失败关闭，返回 401，不调用模型。恢复 Redis 后可访问。这不是“Redis 故障时全站继续可用”的承诺。
- 模型调用次数与用量记录由 MySQL 管理；并发尝试、记录不可用、取消和来源变化由 `AgentIntegrationTest` 覆盖。
- 独立生产 JVM 在 AI/解析关闭、无密钥时运行；强制结束后，用同一隔离数据库重新启动，未完成运行变为 `PROCESS_INTERRUPTED`，未知用量保留、不重放。
- 此前从提交 `0ed89bd` 独立构建旧 JAR，验证同库读取文档、文件夹、会话和调用记录，没有重新初始化数据。当前记录结构已进一步简化，历史演练不能直接证明新旧 Agent 完整兼容，需按当前结构重新验证。
- 回退只证明这两个应用版本间的数据库兼容性，不代表任意历史镜像或基础设施降级都兼容。

## 本轮全量结果与部署阻塞

- 后端 `package`：214 项，208 通过、0 失败/错误、6 项跳过（3 项已有在线模型测试、浏览器夹具、在线40题评测、指定旧 JAR 回退）。后两类可选流程不是“默认运行通过”；旧 JAR 回退另外显式执行过，两项生命周期测试均通过。
- ES 固定集另行 1 项通过；前端 14 项通过。前端构建、后端 JAR 打包和 Compose 静态配置校验通过。
- 全量日志：`target/usage-only-final.log`；回退：`target/p5-lifecycle-rollback.log`；ES：`target/usage-only-es-evaluation.log`。
- **完整 Compose 启动未通过验收**：使用独立项目名、随机测试凭据与回环端口准备空库启动时，固定的 `minio/mc@sha256:3e9666a093d0a8fcbbac606346c415ae9277a0ca96989a6bdddd3d03e90a21b4` 无法拉取。镜像代理返回 403，Docker Hub 直连也未成功；匿名标签接口当时返回 404。不能由此断定镜像永久消失，但当前没有可复现的公开拉取证据。
- 没有把该镜像静默换成 `latest`、第三方镜像或关闭桶初始化；没有改生产 Compose、Docker daemon 镜像源或登录凭据。启动前即停止，未创建该 Compose 项目的业务卷。所用本地 MinIO 服务测试镜像不能替代对部署客户端镜像的验证。
- 应先确认可拉取且可信的固定客户端镜像/内部镜像源，再用独立项目名和空卷重跑全栈检查；现有空库 SQL、真实应用进程和 Nginx 集成验证仍有效，但不能合并包装成“Compose 全栈已通过”。

## 复现

依赖 Java 17、Maven、Docker；使用 Testcontainers 隔离库，不读取业务数据库。在线评测以外的测试不调用模型供应商。

```powershell
# 固定集 MySQL、安全/故障及评测记录器
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=AgentEvaluationTest' test

# 构建与部署一致的 IK 索引镜像后评测 ES 路径
# 已有对应镜像时不需重复构建
docker build -t teamdocs-elasticsearch:8.15.3 docker/elasticsearch
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=AgentEvaluationTest#topSixRecallAndPermissionGate' '-Dteamdocs.eval.elasticsearch=true' test

# 实际生产主类跨 JVM 重启，数据不重置
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=AgentApplicationLifecycleTest' test

# 旧版本 JAR 需先从明确的历史提交独立构建；不要切换/覆盖当前工作区
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=AgentApplicationLifecycleTest#previousPackagedApplicationReadsCurrentSchemaWithoutReinitializingData' '-Dteamdocs.rollback.jar=<旧JAR绝对路径>' '-Dteamdocs.rollback.revision=<提交SHA>' test
```

结果位于 `teamdocs-backend/target/p5-evaluation/`：`retrieval-mysql.json`、`retrieval-elasticsearch.json`、`process-restart.json`、`application-rollback.json`。CI 每次提交仅运行普通测试；固定检索评估在手动运行 CI 时勾选 `run_retrieval_evaluation` 执行，并上传合成评测 JSON，保留 14 天。评估模式不发布镜像。CI 不自动调用外部模型，不自动构建/测试任意回退版本。

## 在线评测与人工复核闸门

在线评测仍是默认关闭的可选测试，40 题真实模型回答和人工复核尚未完成。显式授权后配置 `AGENT_API_KEY`、`AGENT_BASE_URL`、`AGENT_MODEL_NAME`，再运行：

```powershell
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=AgentEvaluationTest#liveFortyQuestionEvaluationRecordsEvidenceWithoutPretendingHumanReview' '-Dteamdocs.live-eval=true' '-Dteamdocs.eval.elasticsearch=true' test
```

脚本只把合成题目和可访问的合成片段发送给供应商，不把答案键传给模型。始终保留每轮 6 次模型/8 次工具/90 秒限制，不自动重试模型请求。报告每题记录终态、原文回答、来源、模型/工具次数、输入输出 Token、总耗时与首次完整 SSE 状态帧时间；多轮题也记录引导运行。未知用量不伪装为零消耗。

`answers-live.json` 的 `humanVerdict` 初始一律是 `PENDING`。复核人应为每题补充 `PASS`/`FAIL` 和原因，核对所有关键事实与引用原文，另记无资料题是否拒绝编造。自动的 `missingLiteralFacts` 不是最终分数；同义表达、时间写法及正确拒答不能只靠字符串判分。测试失败或仅运行部分题时不能宣布 40 题验收完成。

达到安全/引用硬门槛、检索门槛、人工回答门槛并完成部署检查后，才能把本页状态更新为 V0.1 通过。上线前还须由负责人确定真实问答记录的保留期限、清理责任及运维访问权限；当前未新增自动保留期或用户会话删除接口。
