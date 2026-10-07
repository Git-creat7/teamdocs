# 部署说明

默认使用 Docker Compose 单机部署，前后端、MySQL、Redis、MinIO 和 Elasticsearch 一起启动。服务器需要 Linux x86_64、Docker 和 Compose v2，不需要安装 Java、Node.js 或 Maven。

## 1. 下载并配置

```bash
git clone https://github.com/Git-creat7/teamdocs.git
cd teamdocs
cp -n .env.example .env
```

编辑 `.env`，已有配置不要覆盖：

- `IMAGE_TAG`：填写一次成功发布镜像的完整提交 SHA；默认镜像仓库为 `ghcr.io/git-creat7/teamdocs`。
- `DB_PASSWORD`、`REDIS_PASSWORD`、`MINIO_SECRET_KEY`：分别设置密码，不使用模板占位值。
- `JWT_SECRET`：生成随机密钥，例如执行 `openssl rand -hex 32`。
- `WEB_PORT`：网页端口，默认 `15173`。
- `MINIO_PUBLIC_ENDPOINT`：浏览器可访问的 MinIO 文件 API 地址；`MINIO_CORS_ALLOWED_ORIGIN`：实际前端来源，不带 `/login` 等路径。
- 启用问答时填写 `AGENT_BASE_URL`、`AGENT_API_KEY`、`AGENT_MODEL_NAME`，并确认允许问题及相关资料发送给模型。
- 需要保存私人模型配置时，用 `openssl rand -base64 32` 生成 `MODEL_CONFIG_ENCRYPTION_KEY`，妥善保存，不要随意更换。

密码含 `$` 时，在 `.env` 中用单引号包住完整值。真实密码、Key 和 `.env` 不要提交到仓库。

## 2. 拉取并启动

```bash
docker compose config --quiet
docker compose pull
docker compose up -d --wait
docker compose ps -a
```

GHCR 镜像提示权限不足时，执行 `docker login ghcr.io`，使用具有 `read:packages` 权限的 Token。空 MySQL 数据卷首次启动会自动初始化表；`minio-init` 完成后显示 `Exited (0)` 是正常状态。

## 3. 访问地址

| 服务 | 默认宿主机入口 |
|---|---|
| 网页 | `http://localhost:15173/login` |
| 后端 | `127.0.0.1:8080`，仅本机 |
| MinIO 文件 API | `127.0.0.1:29000`，仅本机 |
| MinIO 控制台 | `127.0.0.1:29001`，仅本机 |

前端镜像已转发 `/api`，不必把后端端口开放到公网。MySQL、Redis、ES 默认只在容器网络中使用。

**域名访问：**网页反代到 `127.0.0.1:15173`，文件域名反代到 `127.0.0.1:29000`。配置 HTTPS，文件反代保留原始 Host 和路径，网页反代关闭响应缓冲以支持 SSE；同步填写上面的两个 MinIO 地址配置。

**内网 IP 试用：**把地址改为实际服务器 IP，例如：

```dotenv
MINIO_PUBLIC_ENDPOINT=http://服务器IP:29000
MINIO_CORS_ALLOWED_ORIGIN=http://服务器IP:15173
```

同时把 `minio.ports` 中的 `127.0.0.1:${MINIO_API_PORT:-29000}:9000` 改为 `${MINIO_API_PORT:-29000}:9000`，防火墙按需放行网页和文件端口；控制台仍保持仅本机访问。公网正式使用建议 HTTPS，不要直接暴露管理端口。

## 4. 复用已有 MySQL、Redis 或 MinIO

默认 Compose 的后端地址固定为容器服务名，**只修改 `.env` 不会切换到外部服务**。删除不再使用的服务配置及 `backend.depends_on` 中对应项；复用外部 MinIO 时同时移除 `minio` 和 `minio-init`，不删除原数据卷。然后只把需要复用的组件改为读取环境变量：

```yaml
# backend.environment：按需替换对应项，其他配置保留
DB_HOST: ${DB_HOST}
DB_PORT: ${DB_PORT:-3306}
DB_USERNAME: ${DB_USERNAME}
REDIS_HOST: ${REDIS_HOST}
REDIS_PORT: ${REDIS_PORT:-6379}
MINIO_ENDPOINT: ${MINIO_ENDPOINT}
```

在 `.env` 填写已有服务的实际地址和有效凭据。容器中的 `localhost` 指容器自己；连接宿主机服务时使用容器可达的私网地址，并确认监听地址与账号授权允许 Docker 连接。复用远程 MinIO 时，还需在远程服务上允许实际前端来源。开发和部署环境应隔离 Redis 缓存，避免同名键互相影响。

**已有数据库先备份，再按缺失结构增量升级。只有全新空库才能导入 `sql/init.sql`，该文件含 DROP，不能在有业务数据的库里重跑。修改环境变量或重建容器，也不会自动修改旧数据库的密码。**

## 5. 可选：Milvus 与本地开发

启用语义检索时，填写 Embedding 配置和 `MILVUS_STORAGE_PASSWORD`；需要重排再填写 Reranker 配置。有效 Embedding Key 会使后台为已有可用文档发送正文并建立向量索引，填写前先确认资料出站范围。

```bash
docker compose -f docker-compose.yaml -f docker-compose.semantic.yaml pull
docker compose -f docker-compose.yaml -f docker-compose.semantic.yaml up -d --wait
```

IDEA 开发单独使用 `docker compose -f docker-compose.dev.yaml up -d --wait`，仅启动 ES、Milvus 及其依赖；业务 MySQL、Redis、MinIO 使用已有服务，前后端在本地启动。不要把 dev 文件和完整部署文件合并。

## 6. 更新与排错

更新到目标版本的 Compose 和 SQL 文件，保留自己的 `.env` 与外部服务配置；将 `IMAGE_TAG` 改为已发布版本，再执行：

```bash
docker compose pull
docker compose up -d --wait
docker compose logs --tail=100 backend
```

启用了 Milvus 时，更新和查看状态也使用相同的两个 `-f` 文件。只改配置不必重建镜像，但需要 `up -d` 重新创建受影响的容器；单纯 `restart` 不会加载新的环境变量。

镜像名称不对时用 `docker compose config --images` 检查；后端 unhealthy 先看日志。`Access denied`、`WRONGPASS` 优先核对实际连接目标与凭据，TLS 错误先核对证书和访问路径，不关闭证书校验。不要使用 `down -v` 或 `--remove-orphans` 来处理启动故障。

## MinIO 镜像来源

MinIO 和 mc 使用 GHCR 上的固定摘要镜像，不随 `IMAGE_TAG` 更新，也不在部署时编译 MinIO 源码。当前固定版本较旧，生产使用前需评估维护风险与访问控制；Fork 的镜像同步方式见 [Mirror MinIO workflow](../.github/workflows/mirror-minio.yml)。

