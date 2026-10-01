# P2.5 Elasticsearch 关键词检索

## 边界与版本

- Elasticsearch、Java API Client、低层 REST Client 和 IK 插件统一锁定 **8.15.3**。沿用现有备份的版本，不升级 Java 17 / Spring Boot 3.5.14。
- [Elastic 官方兼容说明](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/8.15/introduction.html)：Java 客户端向相同主版本、相同或更高次版本的服务端兼容。本项目直接使用相同版本，避免依赖 Spring Boot BOM 意外换成其他 REST Client 版本。
- 8.15 文档已停止更新；公开上线前需另行评估漏洞和版本升级，本阶段的通过不等于长期安全支持。
- MySQL 是事实来源；ES 只存当前 READY、文档和空间都未删除的分块。文档 ID 为 `documentId_chunkIndex`，额外保存 `chunk_id`、空间和解析版本。
- ES 用 IK (`ik_max_word` / `ik_smart`) + BM25；IK 缺失时报错，不静默改成 standard 分词。
- 不改变原文档名/标签搜索，不增加向量数据库、Agent 循环或聊天页面。

## 检索与一致性

1. 先校验当前空间成员，ES 最多召回 6 个候选。
2. 每个候选按空间、文档、chunkId、parseVersion 回 MySQL 复核，过滤已删除、非 READY、版本变化等状态。
3. ES 失败、没有结果或结果不足时，用 MySQL FULLTEXT 补足并按 chunkId 去重。返回内容仍受总字符上限约束。
4. `excerpt` 始终来自 MySQL。`highlight` 是单独的可选字段：ES 先转义 HTML，只允许 `<mark>`，且必须能对应实际返回范围内的正文；高亮不会覆盖正文或伪造字符偏移。调用模型时应使用原文，不把展示标记当成新证据。
5. 解析发布/重解析、软删除、恢复和彻底删除后同步索引；事务中的操作在提交后同步，回滚不发布。同步重新读 MySQL 当前状态，而不是使用旧 Worker 快照。
6. 单次连接/连接池等待上限 1 秒、响应读取上限 2 秒。同步异常不回滚主业务；Bulk 的逐项失败也视作失败并记录。没有引入 MQ 或持续重试；遗漏项由 MySQL 降级兜底，重建负责修复。
7. 并发操作仍可能短暂留下旧索引；返回前的 MySQL 复核保证旧版本不可读。全量重建期间也可能短暂回退 MySQL。

## 部署前检查

本次只能检查开发机，不能据此宣称生产服务器内存足够。部署前在**目标机器**检查：

```sh
free -h
docker stats --no-stream
sysctl vm.max_map_count
```

建议整套服务至少 8 GiB 内存；需为 ES 预留 1.5 GiB 容器内存以及其他服务、系统余量。内存不足时先扩容，不要直接启动全栈或依靠 OOM 重启。Linux 的 `vm.max_map_count` 至少为 262144，低于该值需由管理员调整。

Compose 中 ES 堆固定为 512 MiB，容器限制 1536 MiB；只有容器网络访问，没有映射 9200 到宿主机。关闭了 ES 认证，因此不得把该网络或端口暴露到公网。后端不依赖 ES 健康检查完成才能启动，ES 不可用时业务走 MySQL。

CI 构建带 IK 的 ES 镜像，与后端、前端一起以提交 SHA 发布到 GHCR；三份镜像均成功后才更新 latest。服务器使用相同 `IMAGE_TAG` 拉取整套镜像，不要求服务器构建 IK。

```sh
docker compose pull
docker compose up -d
```

本机原生启动后端时：`ES_ENABLED=false` 保持 MySQL-only；或配置可访问的 `ES_URL`。Compose 会将 URL 覆盖为容器内地址。

## 全量重建入口

只允许运维通过一次性后端进程调用，不新增全局 HTTP 管理接口。**重建会删除并重建 `ES_INDEX` 指定的专用索引，不能指向其他应用的索引。** MySQL 和 MinIO 不做删除或回写。

在服务已启动、镜像已更新的服务器执行：

```sh
docker compose run --rm --no-deps backend \
  --teamdocs.elasticsearch.enabled=true \
  --teamdocs.elasticsearch.rebuild=true \
  --teamdocs.parse.enabled=false \
  --teamdocs.agent.enabled=false \
  --server.port=0
```

成功日志会输出当前分块数量，然后进程退出；失败返回非零退出码。分块按主键每批最多 200 条读取，清理过时条目，不把全库正文一次装入内存。可重复执行。只运行一个重建进程；业务读请求在期间可降级到 MySQL。

## 本地验证

```sh
docker build -t teamdocs-elasticsearch:8.15.3 docker/elasticsearch
mvn -B -ntp -f teamdocs-backend/pom.xml test
```

测试创建独立 MySQL、MinIO、ES+IK 容器，不访问开发业务库。若 MinIO 官方镜像无法拉取，可通过 `-Dteamdocs.test.minio-image=<已验证的测试镜像>` 指定本机镜像；ES 可通过 `-Dteamdocs.test.elasticsearch-image=<带 IK 的 8.15.3 镜像>` 指定。默认测试不会调用真实模型。

P2 与 P2.5 共用 `src/test/resources/retrieval-samples.sql`，比较中文、分离多词、API、zip 的召回，不要求两种分词器给出相同排名。真实集成测试还覆盖索引同步/恢复、来源高亮、伪造空间候选、旧版本、删除和未 READY 过滤、撤销成员、部分漏索引、重复重建和 ES 停服时的上传/解析/预览/检索。

## 2026-09-28 本地验收记录

- 后端 `mvn -B -ntp -f teamdocs-backend/pom.xml -Dteamdocs.test.minio-image=teamdocs-minio-test:20230920 package`：174 项，172 通过，2 项真实模型测试按默认配置跳过，无失败或错误，JAR 打包成功。
- 前端 `npm run build` 通过；Compose 使用临时测试密码解析通过。
- 从本目录 Dockerfile 构建 `teamdocs-elasticsearch:p25-verify` 成功；再用该新镜像复跑 11 项真实生命周期/检索集成测试，全通过。没有向 GHCR 发布或部署服务器。
- 打包后的完整应用分别在 ES 关闭、ES 开启但地址不可达时启动成功，未认证接口均返回 401。该启动检查把 MySQL、Redis、MinIO 和 ES 全部指向未使用的 loopback 端口，不连接开发业务库；它只验证装配和认证入口，不代表这些依赖的健康检查通过。
- 开发机 Docker 虚拟机约 7.4 GiB 内存，检查时约 6 GiB 可用，真实容器测试通过。生产目标机器未检查。

同一固定样例集的实际召回（`文档ID#分块序号`）：

| 查询 | MySQL FULLTEXT | Elasticsearch IK / BM25 |
|---|---|---|
| 上线检查 | 10#0 | 10#0、15#0 |
| 回滚 备份 | 10#0、10#1 | 10#1、10#0 |
| api | 16#0 | 16#0 |
| zip | 16#0 | 16#0 |

ES 的多词召回和排序与 MySQL 不同：回滚相关段落排在前面；“上线检查”也会召回仅包含“上线”的会议室片段 15#0。这里记录实际差异，不声称整体精度已提高，也不据此引入向量检索。所有返回片段仍需通过空间和版本复核。
