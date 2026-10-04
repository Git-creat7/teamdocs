# 固定 MinIO / mc 镜像同步

采用“可访问的镜像缓存 → 自己的 GHCR”方案，不编译源码、不用浮动 latest，也不修改原有 MinIO/etcd 数据卷。

## 已核对的版本与摘要

本项目目前部署目标为 Linux amd64。同步单平台 manifest，不伪装成多架构镜像。

| 组件 | 实际二进制版本 | GHCR 固定引用 |
|---|---|---|
| MinIO | RELEASE.2024-05-28T17-19-04Z | `ghcr.io/git-creat7/teamdocs/minio@sha256:648817f3b321ec7a2f86c594ba468fa19eff8ee3ac17a07c03acf7a8a35fda33` |
| mc | RELEASE.2025-08-13T08-35-41Z | `ghcr.io/git-creat7/teamdocs/mc@sha256:eb4ea9884b77704230e2423e9004d2fa738dc272876b9cc41a297d29443b8780` |

来源分别是 `docker.m.daocloud.io/minio/minio`、`docker.m.daocloud.io/minio/mc` 的同一摘要。DaoCloud 是第三方缓存，不是 MinIO 官方仓库；已经读取并执行镜像的版本信息，发布后再次验证目标摘要与版本。摘要保证选定镜像不可变以及同步前后内容一致，不等同于独立验证所有上游供应链签名。官方历史二进制校验和下载端点核验时返回410，不能声称完成了这一步验证。

本机此前两个名为2023/2024的MinIO标签指向同一份2024镜像，不能拿标签当成版本证据。这里以运行结果为准：MinIO commit `f79a4ef4d0dc3e6562cad0d1d1db674bc8c75531`，mc commit `7394ce0dd2a80935aded936b09fa12cbb3cb8096`。

## 同步流程

1. 已把本机按上述摘要拉取的镜像导出为包含原始manifest与layer的目录归档，放在仓库的 `minio-mirror-seed-20261004` 预发布附件中作为一次性中转；这不是应用版本。GitHub runner 访问 DaoCloud 被403拒绝，因此从GitHub中转下载，不再依赖镜像源对runner的访问策略。workflow固定校验归档和manifest的SHA-256。
2. 在 GitHub Actions 手动执行 [Mirror MinIO images](../../.github/workflows/mirror-minio.yml)。它仅复制固定摘要，不接收任意地址输入、不部署服务。
3. workflow 用本仓库临时 `GITHUB_TOKEN` 的 `packages:write` 权限同步，之后从 GHCR 按原摘要拉取并检查版本；不在仓库保存长期令牌。
4. 如需匿名部署或接受 Fork PR，在 GitHub Packages 中将这两个包设为 Public；否则部署机器需要 GHCR 只读权限。仓库自身 CI 使用 `packages:read` 和临时 Token。
5. 手动触发 CI（不勾选检索评估）或正常提交代码；日常测试只拉 GHCR 摘要，不重复同步镜像，也不编译 MinIO。

镜像标签仅辅助识别，例如 `RELEASE.2024-05-28T17-19-04Z-amd64`；应用和测试实际引用摘要。同步命令使用 `skopeo copy --preserve-digests`，保留源平台 manifest 和压缩层，而非重新打包容器；目标manifest可由原摘要直接拉取。中转附件约83MiB，不放入Git历史。

## 本地验证

```bash
mvn -B -ntp -f teamdocs-backend/pom.xml '-Dtest=MinioRuntimeIntegrationTest,AgentSseIntegrationTest,DocumentLifecycleIntegrationTest' test
# 可选：同一版本作为独立Milvus对象存储验证，不连接业务库
mvn -B -ntp -f teamdocs-backend/pom.xml -Dtest=MilvusContainerIntegrationTest -Dteamdocs.test.milvus=true test
```

`MinioRuntimeIntegrationTest` 执行原 Compose 初始化脚本两次，验证其幂等、公私桶隔离、对象写入/读取、预签名 URL 和 CORS。Server 与 mc 是两个镜像；业务与 Milvus 存储依旧使用不同数据卷和凭据。可通过测试属性 `teamdocs.test.minio-image` / `teamdocs.test.minio-client-image` 指定测试镜像，但日常 CI 不靠临时替换隐藏上游错误。

## 维护与安全边界

MinIO 社区仓库已标注不再维护和源码分发。这里保留兼容性较好的历史版本，只解决镜像分发问题；2024服务器镜像并非最新安全版本，不包含后续全部安全修复。生产/公网部署必须单独评估升级、网络隔离、备份和许可证义务。不能把“功能测试通过”当作“漏洞已修复”。上游协议为AGPLv3，镜像同步不改变其许可。

升级需要先核对新版本真实来源和摘要，在隔离环境及数据副本验证后更新固定引用；不要直接覆盖运行中的旧数据卷，也不要假定可降级存储格式。本次不迁移到AIStor或其他需要额外授权的产品。
