# 单机部署

TeamDocs 只使用根目录的 `docker-compose.yaml`。GitHub Actions 负责测试和发布镜像，服务器负责拉取并启动；不在服务器构建源码，不需要部署脚本。Nginx 已包含在前端镜像中，用于托管页面和转发 `/api`。

## 首次发布镜像

将代码推送到 `main`，或在 `main` 上手动运行仓库的 **CI** workflow。Pull Request 只做检查，不发布镜像。

后端测试、前端构建与 Compose 校验通过后，CI 发布：

```text
ghcr.io/git-creat7/teamdocs/backend:<完整提交 SHA>
ghcr.io/git-creat7/teamdocs/frontend:<完整提交 SHA>
```

两份镜像发布成功后才更新 `latest`。发布版本会显示在 Actions 运行摘要中。首次部署前必须先有一次成功发布；仓库公开不代表镜像包公开，需要分别把 GHCR 的 backend、frontend 包设为 Public，或者在服务器执行 `docker login ghcr.io`，使用具有 `read:packages` 权限的令牌登录。令牌不要写入仓库或 Compose 文件。

Fork 后 CI 会发布到自己的仓库命名空间，需要同步修改 `.env` 中的 `IMAGE_REPOSITORY`；仓库路径使用小写。

## 准备与启动

前置条件：Linux x86_64 服务器、Docker Engine、Docker Compose v2，可访问 GHCR 和 Docker Hub。当前 CI 构建 `linux/amd64` 应用镜像。服务器只需要 `docker-compose.yaml`、`sql/` 和自己的 `.env`；克隆仓库是获取并同步这些文件的便捷方式，不会在服务器编译源码。

```bash
git clone https://github.com/Git-creat7/teamdocs.git
cd teamdocs
cp -n .env.example .env
```

编辑 `.env` 后再启动，已有配置不要覆盖：

- 填写不同的 `DB_PASSWORD`、`REDIS_PASSWORD`、`MINIO_SECRET_KEY`，以及至少 32 字节的随机 `JWT_SECRET`。Linux 可用 `openssl rand -hex 32` 每次生成一个值。已有 MySQL 卷不会因修改环境变量而自动修改库内密码。
- 正式部署推荐将 `IMAGE_TAG` 设为一次完整成功发布的提交 SHA，使前后端版本一致。`latest` 适合本机快速体验，不作为可追溯的版本记录。
- 本机浏览器体验可保留模板中的 `localhost` URL；远程访问先按下一节配置 HTTPS 文件域名与前端来源。
- 密码含 `$` 时用单引号包住完整值，例如 `DB_PASSWORD='a$password'`。不要把真实 `.env` 或解析后的完整配置上传到仓库。

```bash
docker compose config --quiet
docker compose pull
docker compose up -d --wait
docker compose ps -a
```

首次启动会自动创建 `teamdocs_mysql_data`、`teamdocs_redis_data`、`teamdocs_minio_data` 三个命名卷，并在空 MySQL 卷中初始化表和全文索引。`minio-init` 创建公私桶后正常退出，状态为 `Exited (0)`；后端等待数据库、Redis 和桶初始化就绪，前端等待后端健康检查通过。

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

启动后先确认 `docker compose ps -a` 无失败服务，再从实际浏览器来源验证登录、空间列表、文件上传、预览和下载。仅检查 Web 的 `/healthz` 无法证明后端代理或外部文件地址可用；应同时检查上面的 `/api/actuator/health` 和文件链路。

本机原生开发不使用这份 Compose。
