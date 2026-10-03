# 单机部署

TeamDocs 只使用根目录的 `docker-compose.yaml`。GitHub Actions 负责测试和发布镜像，服务器负责拉取并启动；不在服务器构建源码，不需要部署脚本。Nginx 已包含在前端镜像中，用于托管页面和转发 `/api`。

交付以全新服务器和空 MySQL 数据目录为准，数据库结构统一维护在 `sql/` 初始化脚本中，首次启动时自动创建。

## 首次发布镜像

将代码推送到 `main`，或在 `main` 上手动运行仓库的 **CI** workflow。Pull Request 只做检查，不发布镜像。

后端测试、前端构建与 Compose 校验通过后，CI 发布：

```text
ghcr.io/git-creat7/teamdocs/backend:<完整提交 SHA>
ghcr.io/git-creat7/teamdocs/frontend:<完整提交 SHA>
ghcr.io/git-creat7/teamdocs/elasticsearch:<完整提交 SHA>
```

三份镜像发布成功后才更新 `latest`。发布版本会显示在 Actions 运行摘要中。首次部署前必须先有一次成功发布；仓库公开不代表镜像包公开，需要分别把 GHCR 的 backend、frontend、elasticsearch 包设为 Public，或者在服务器执行 `docker login ghcr.io`，使用具有 `read:packages` 权限的令牌登录。令牌不要写入仓库或 Compose 文件。

Fork 后 CI 会发布到自己的仓库命名空间，需要同步修改 `.env` 中的 `IMAGE_REPOSITORY`；仓库路径使用小写。

## 当前待解决的镜像依赖

2026-09-29 的 P5 空库启动准备中，固定的 `minio/mc` 客户端镜像未能从所用镜像源拉取（代理 403、直连未成功）。因此**本轮没有完成完整 Compose 启动验收**。先确认可用且可信的固定镜像来源，再部署；不要跳过 `minio-init` 或删除现有数据卷来规避。

## 准备与启动

前置条件：Linux x86_64 服务器、Docker Engine、Docker Compose v2，可访问 GHCR 和 Docker Hub。当前 CI 构建 `linux/amd64` 应用镜像。服务器只需要 `docker-compose.yaml`、`sql/` 和自己的 `.env`；克隆仓库是获取并同步这些文件的便捷方式，不会在服务器编译源码。

```bash
git clone https://github.com/Git-creat7/teamdocs.git
cd teamdocs
cp -n .env.example .env
```

部署前请核实目标机器内存与内核参数，内存不足时不要直接启动全栈。启用只读 Agent 还需配置模型并确认文档出站授权；默认不会发送真实空间内容。

编辑 `.env` 后再启动，已有配置不要覆盖：

- 填写不同的 `DB_PASSWORD`、`REDIS_PASSWORD`、`MINIO_SECRET_KEY`，以及至少 32 字节的随机 `JWT_SECRET`。Linux 可用 `openssl rand -hex 32` 每次生成一个值。已有 MySQL 卷不会因修改环境变量而自动修改库内密码。
- 正式部署推荐将 `IMAGE_TAG` 设为一次完整成功发布的提交 SHA，使前后端和 ES 镜像版本一致。`latest` 适合本机快速体验，不作为可追溯的版本记录。
- `MINIO_CORS_ALLOWED_ORIGIN` 填写浏览器实际前端来源，当前模板为 `http://localhost:15173`；修改 `WEB_PORT`、使用域名或 HTTPS 时同步修改该值，不带页面路径。
- 密码含 `$` 时用单引号包住完整值，例如 `DB_PASSWORD='a$password'`。不要把真实 `.env` 或解析后的完整配置上传到仓库。

```bash
docker compose config --quiet
docker compose pull
docker compose up -d --wait
docker compose ps -a
```

首次启动会自动创建 `teamdocs_mysql_data`、`teamdocs_redis_data`、`teamdocs_minio_data`、`teamdocs_elasticsearch_data` 四个命名卷，并在空 MySQL 卷中初始化表和全文索引。`minio-init` 创建公私桶后正常退出，状态为 `Exited (0)`；后端等待数据库、Redis 和桶初始化就绪，前端等待后端健康检查通过。

默认端口如下，可在 `.env` 调整宿主机端口，不需要修改容器内端口：

| 服务 | 宿主机入口 | 用途 |
|---|---|---|
| Web | `:15173` | 页面和同源 `/api` |
| Backend | `127.0.0.1:8080` | 本机 API 调试与健康检查 |
| MinIO S3 API | `127.0.0.1:29000` | 文件域名反代目标 |
| MinIO Console | `127.0.0.1:29001` | 本机管理入口 |
| MySQL / Redis | 不发布端口 | 仅容器内部访问 |

## 更新与回退

等待目标版本的 CI 全部成功，在 `.env` 记录该版本的完整提交 SHA，然后执行：

```bash
docker compose pull
docker compose up -d --wait
docker compose ps -a
curl --fail http://127.0.0.1:15173/api/actuator/health
```

若本次发布修改了 Compose 或 SQL，先同步目标版本的仓库文件；跟随 `main` 可用 `git pull --ff-only`，并确认所选 SHA 与配置版本匹配。不要只拉取一端镜像，也不要在 `latest` 标签更新过程中发布。前端 Nginx 会重新解析 Docker DNS，因此后端容器替换后不需要额外手动重启前端。

回退时把 `IMAGE_TAG` 改回上一个成功版本的完整 SHA，执行相同命令；配置有变更时一并恢复该版本配置。单机容器替换可能短暂中断请求，健康检查失败不会自动回退，应查看 `docker compose logs --tail=100 backend frontend` 并决定是否退回旧版本。

应用回退不会恢复数据库结构或数据。初始化 SQL 仅在 MySQL 数据卷为空时执行，禁止更新时重复执行包含 `DROP TABLE` 的 `init*.sql`。

普通更新无需 `down`。`docker compose down` 保留命名卷；`docker compose down -v` 会删除数据，不要用于发布或处理初始化问题。数据库与文件应分别备份，基础设施镜像摘要的升级单独安排。

## 验收

后台解析支持 UTF-8 TXT、Markdown、文本型 PDF 和 DOCX，默认文件上限 10MB、正文上限 200,000 字符。扫描件、加密文件及不支持的格式会显示明确原因，原文件仍可下载，预览沿用原有支持范围。可通过 `.env` 中的 `PARSE_ENABLED=false` 停止后台扫描；解析大小、字符数、超时和重试间隔分别由 `PARSE_MAX_BYTES`、`PARSE_MAX_CHARS`、`PARSE_TIMEOUT_SECONDS`、`PARSE_RETRY_DELAY_SECONDS` 设置。

启动后先确认 `docker compose ps -a` 无失败服务，再从实际浏览器来源验证登录、空间列表、文件上传、预览和下载。仅检查 Web 的 `/healthz` 无法证明后端代理或外部文件地址可用；应同时检查上面的 `/api/actuator/health` 和文件链路。

本机原生开发不使用这份 Compose。


## P5 回退与数据保留验证

应用回退前先清空模型 Key，或在对应版本支持时通过 `--teamdocs.agent.enabled=false` 等 Spring 属性关闭功能；旧版仍按其开关配置操作。记录目标完整提交 SHA，确认旧应用与现存 SQL 结构兼容后只更换应用镜像。不要为了回退重新运行初始化 SQL、删表或删除数据卷。仍在执行的模型请求可能未立即结束；终态未知的请求不得自动重放。

2026-09-29 已在独立空库演练当前主类进程重启，以及回退至 `0ed89bd` 构建的旧 JAR：文档、文件夹、会话和未知用量均保留，未重新初始化数据。此记录是本地应用兼容性证据，不是生产镜像发布/线上部署结果，详见 [P5 评测与命令](AGENT_EVALUATION.md)。

上线前另行确定问答记录保留期限、清理责任与运维访问范围。当前会话没有用户删除入口，也没有自动 TTL；不能把测试报告的 14 天保留期误当作业务问答的保留策略。原始问题、文档衍生回答和部署密钥不得上传到 CI 制品。

Redis 故障时 JWT 撤销校验按安全策略拒绝认证，可能需要恢复后重新登录；Agent 额度凭证实际在 MySQL。模型故障不应降低文档服务健康状态，但不能据此推导数据库/Redis 故障也完全无影响。


### 应用与表结构兼容

新空库使用当前 `sql/agent.sql` 的模型调用记录定义。已有旧版本数据库必须先备份并核对列、索引和约束，应用不会自动变更结构；本次未操作实际业务数据库。

此前 `0ed89bd` 回退演练只覆盖当时的表结构。不要据此在当前结构下继续开放旧版 Agent；需选择兼容版本重新验证，或关闭 Agent 仅保留核心文档管理降级。
