# TeamDocs Backend

TeamDocs 后端服务：Spring Boot 3.5 + MyBatis-Plus + MySQL 8 + Redis 7 + MinIO。提供认证、空间权限、文档管理、标签、评论、操作日志、缓存与限流。

## 目录

- [本地开发](#本地开发)
- [Docker Compose 一键启动](#docker-compose-一键启动)
- [环境变量](#环境变量)
- [API 约定](#api-约定)
- [在线预览接口](#在线预览接口)
- [API 冒烟测试](#api-冒烟测试)
- [常见问题](#常见问题)

## 本地开发

前置：Linux Bash、Java 17，以及本机可访问的 MySQL、Redis、MinIO。以下命令在 `teamdocs-backend` 目录执行。

```bash
# 1. 复制环境模板，已有配置不覆盖
cp -n ../.env.example .env
# 2. 在 .env 填写本机 DB、Redis、MinIO 连接地址和密钥
# 3. 运行后端（默认端口 8080）
sh ./mvnw spring-boot:run
# 跑测试
sh ./mvnw test
```

环境变量也可以直接配置到操作系统，不强制使用 `.env` 文件。

后端启动后，前端开发代理 `teamdocs-frontend/vite.config.js` 默认指向 `http://localhost:8080`。根目录 Compose 不向宿主机暴露 MySQL 和 Redis；本机运行后端时需要另外准备可访问的依赖。

## Docker Compose 一键启动

仓库根目录的 `docker-compose.yaml` 从 GHCR 拉取前后端镜像，同时启动 MySQL、Redis、MinIO 和桶初始化服务。环境配置、首次启动、更新与反向代理统一见 [部署文档](../docs/DEPLOYMENT.md)。

前置：Docker Engine 与 Docker Compose v2 可用，已按部署文档准备根目录 `.env`。

以下 Compose 命令均在仓库根目录执行：

```bash
docker compose pull
docker compose up -d --wait
docker compose ps -a
docker compose logs -f backend
```

默认入口：Web `http://localhost:15173`，API `http://127.0.0.1:8080`，健康检查 `http://127.0.0.1:8080/actuator/health`，MinIO S3 API `http://127.0.0.1:29000`，MinIO Console `http://127.0.0.1:29001`。API 与 MinIO 仅绑定本机；MySQL 和 Redis 只在 Compose 内部网络开放。

停止容器并保留数据卷：

```bash
docker compose down
```

MySQL 初始化 SQL 只在数据卷为空时运行。不要通过删除数据卷重新执行初始化 SQL，详见部署文档。

## 环境变量

- `IMAGE_REPOSITORY` / `IMAGE_TAG`：Compose 使用的 GHCR 镜像仓库和版本
- `WEB_PORT` / `BACKEND_PORT`：Compose 宿主机端口，默认 `15173` / `8080`
- `DB_NAME` / `DB_PASSWORD`：MySQL 数据库名和 root 密码
- `REDIS_PASSWORD`：Redis 密码
- `JWT_SECRET`：JWT HMAC 密钥，至少 32 字节
- `MINIO_API_PORT` / `MINIO_CONSOLE_PORT`：MinIO API 与控制台宿主机端口，默认 `29000` / `29001`
- `MINIO_PUBLIC_ENDPOINT`：必填，返回给浏览器的文件访问地址；服务器部署使用可访问的 HTTPS 文件域名
- `MINIO_CORS_ALLOWED_ORIGIN`：必填，允许读取预签名资源的前端来源，必须是精确的 `scheme://host[:port]`
- `MINIO_REGION`：MinIO 区域，默认 `us-east-1`，后端与服务端必须一致
- `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`：MinIO 管理账号和密码
- `MINIO_BUCKET_PUBLIC` / `MINIO_BUCKET_PRIVATE`：公有桶和私有桶名称

`DB_HOST`、`DB_PORT`、`DB_USERNAME`、`REDIS_HOST`、`REDIS_PORT` 和 `MINIO_ENDPOINT` 用于本机启动后端时连接依赖。Compose 会把连接配置设置成容器网络内的服务地址，并使用 MySQL root 用户。如果 Compose `.env` 中的密码含有 `$`，用单引号包住完整值，例如 `DB_PASSWORD='a$password'`。

Compose 内部使用 `http://minio:9000` 连接 MinIO，但下载链接必须使用客户端能访问的 `MINIO_PUBLIC_ENDPOINT`。MinIO 通过 `MINIO_API_CORS_ALLOW_ORIGIN` 接收根目录 `.env` 中的 `MINIO_CORS_ALLOWED_ORIGIN`；修改后在仓库根目录执行 `docker compose up -d --wait`，由 Compose 更新容器配置。本机单独运行 MinIO 时需要在该服务上配置相同的允许来源。

公网访问通过反向代理或 FRP 接入 TLS 入口，文件域名应转发至 S3 API，而非 Console。CORS 响应头由 MinIO 返回，反向代理不要重复添加；具体配置见 [部署文档](../docs/DEPLOYMENT.md)。

## API 约定

注册、登录和健康检查允许匿名访问，其余接口需要请求头：

```text
Authorization: Bearer <token>
```

项目的业务异常统一返回 HTTP 200。真正的业务结果看响应体：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

- `code = 1`：业务成功
- `code = 0`：业务失败，原因见 `msg`
- JWT 缺失、格式错误、已注销或失效：HTTP 401，并返回 `code = 0` 的统一 JSON

主要路由：

| 模块 | 方法 / 路径 |
|---|---|
| 用户 | `POST /user/register`、`POST /user/login`、`POST /user/logout`、`GET /user/info`、`PUT /user/profile`、`PUT /user/password`、`POST /user/avatar` |
| 最近浏览 | `GET /user/recent-documents` |
| 空间与成员 | `GET /space/list`、`POST /space`、`GET/PUT/DELETE /space/{id}`、`POST /space/{id}/members`、`GET /space/{id}/members`、`PUT/DELETE /space/{id}/members/{userId}` |
| 活动流 | `GET /activities` |
| 文件夹 | `POST/GET /spaces/{spaceId}/folders`、`PUT/DELETE /spaces/{spaceId}/folders/{folderId}`、`PUT /spaces/{spaceId}/folders/{folderId}/move` |
| 文档 | `POST /spaces/{spaceId}/documents/upload`、`GET /spaces/{spaceId}/documents`、`GET /spaces/{spaceId}/documents/{documentId}`、`PUT .../rename`、`PUT .../move`、`GET .../download`、`GET .../preview`、`DELETE .../{documentId}`、`GET /spaces/{spaceId}/documents/trash`、`PUT .../{documentId}/restore`、`DELETE .../{documentId}/purge`、`GET /spaces/{spaceId}/documents/search` |
| 标签 | `POST/GET /spaces/{spaceId}/tags`、`PUT/DELETE /spaces/{spaceId}/tags/{tagId}`、`POST/DELETE /spaces/{spaceId}/documents/{documentId}/tags/{tagId}`、`GET /spaces/{spaceId}/documents/{documentId}/tags`、`GET /spaces/{spaceId}/documents/tags?documentIds=...`（批量）、`GET /spaces/{spaceId}/tags/{tagId}/documents` |
| 评论 | `POST/GET /spaces/{spaceId}/documents/{documentId}/comments`、`DELETE .../comments/{commentId}` |

文档上传使用 `multipart/form-data`，文件字段名是 `file`；根目录使用 `folderId=0`。发表评论时 `replyToId` 可为空。

文档列表、回收站、搜索、按标签筛选和评论列表支持数据库分页。查询参数 `current` 默认 `1`，`size` 默认 `20`、最大 `100`，响应中的 `data` 结构为：

```json
{
  "records": [],
  "total": 0,
  "current": 1,
  "size": 20,
  "pages": 0
}
```

## 在线预览接口

`GET /spaces/{spaceId}/documents/{documentId}/preview`，需要空间成员权限。返回：

```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "documentId": 1,
    "name": "需求文档.pdf",
    "fileType": "pdf",
    "fileSize": 102400,
    "url": "https://files.example.com/teamdocs-private/xxx?X-Amz-...&response-content-disposition=inline"
  }
}
```

- `url` 是 MinIO 预签名地址，有效期 1 小时，`response-content-disposition=inline`（浏览器内联打开），与下载接口的 `attachment`（触发保存）区分
- 支持浏览器/前端可渲染的格式：图片、文本、PDF、Word、表格、演示文稿与 OFD（文件本体不做解析，预览渲染由前端 file-viewer 完成）
- 前端直接跨域加载预签名 URL，因此 MinIO 的 CORS 必须允许前端来源（见环境变量 `MINIO_CORS_ALLOWED_ORIGIN`）

## API 冒烟测试

前置：Linux Bash、`curl`、`jq`，以及可访问的测试后端和文件服务。下面的流程动态创建账号、空间和文档，校验上传、下载内容、最近浏览和注销；任一步失败都会以非零状态退出，不打印 JWT 或预签名 URL。

仅在测试环境执行。流程会留下测试账号、空间和文档等业务数据，本机临时文件会自动清理。

```bash
bash <<'BASH'
set +x
set -euo pipefail

base_url='http://localhost:8080'
username="demo$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
password="$(od -An -N10 -tx1 /dev/urandom | tr -d ' \n')"
space_name="smoke-$username"
smoke_dir=$(mktemp -d -t teamdocs-smoke.XXXXXX)
trap 'rm -f -- "$smoke_dir/source.txt" "$smoke_dir/downloaded.txt"; rmdir -- "$smoke_dir"' EXIT

# 同时检查 HTTP 状态与业务状态，响应仅供后续步骤读取
api() {
  local response
  response=$(curl --fail --silent --show-error --connect-timeout 10 --max-time 60 "$@") || return $?
  jq -ce 'if .code == 1 then . else error(.msg // "业务请求失败") end' <<< "$response"
}

# 注册并登录，账号与密码长度符合接口要求
account_body=$(jq -nc --arg username "$username" --arg password "$password" \
  '{username: $username, password: $password}')
api -X POST "$base_url/user/register" -H 'Content-Type: application/json' \
  --data "$account_body" >/dev/null
token=$(api -X POST "$base_url/user/login" -H 'Content-Type: application/json' \
  --data "$account_body" | jq -er '.data | strings | select(length > 0)')
auth=(-H "Authorization: Bearer $token")

# 新建空间并动态取得 ID
space_body=$(jq -nc --arg name "$space_name" '{name: $name, description: "接口冒烟测试"}')
api -X POST "$base_url/space" "${auth[@]}" -H 'Content-Type: application/json' \
  --data "$space_body" >/dev/null
space_id=$(api "$base_url/space/list" "${auth[@]}" | \
  jq -er --arg name "$space_name" '.data[] | select(.name == $name) | .id')

# 上传小文件，查询新建文档后校验下载内容
printf 'TeamDocs 接口冒烟测试\n' > "$smoke_dir/source.txt"
api -X POST "$base_url/spaces/$space_id/documents/upload?folderId=0" "${auth[@]}" \
  -F "file=@$smoke_dir/source.txt;type=text/plain" >/dev/null
document_id=$(api "$base_url/spaces/$space_id/documents?folderId=0" "${auth[@]}" | \
  jq -er '.data.records[] | select(.name == "source.txt") | .id')
download_url=$(api "$base_url/spaces/$space_id/documents/$document_id/download" "${auth[@]}" | \
  jq -er '.data | strings | select(length > 0)')
curl --fail --silent --show-error --connect-timeout 10 --max-time 60 \
  --output "$smoke_dir/downloaded.txt" "$download_url"
cmp -- "$smoke_dir/source.txt" "$smoke_dir/downloaded.txt"

# 最近浏览异步写入，最多等待 10 秒并校验对应文档
for attempt in {1..10}; do
  recent=$(api "$base_url/user/recent-documents" "${auth[@]}")
  if jq -e --argjson id "$document_id" 'any(.data[]; .documentId == $id)' <<< "$recent" >/dev/null; then
    break
  fi
  sleep 1
done
jq -e --argjson id "$document_id" 'any(.data[]; .documentId == $id)' <<< "$recent" >/dev/null

# 注销后验证同一令牌已无法访问用户信息
api -X POST "$base_url/user/logout" "${auth[@]}" >/dev/null
status=$(curl --silent --show-error --connect-timeout 10 --max-time 60 \
  --output /dev/null --write-out '%{http_code}' "$base_url/user/info" "${auth[@]}")
[[ "$status" == 401 ]] || { printf '注销验证失败，HTTP 状态：%s\n' "$status" >&2; exit 1; }
printf '通过：账号 %s，空间 %s，文档 %s，下载、最近浏览和注销验证完成。\n' \
  "$username" "$space_id" "$document_id"
BASH
```

## 常见问题

- **端口被占用**：修改根目录 `.env` 中对应的宿主机端口；容器内部端口无需修改。前端本地开发还需同步 Vite 代理目标。
- **初始化 SQL 没有执行**：初始化脚本只在 MySQL 数据卷为空时执行，重启容器不会重复执行。可检查 MySQL 日志确认首次初始化结果，不要删除数据卷排障。
- **下载 URL 中出现 `minio:9000`**：检查后端是否设置了 `MINIO_PUBLIC_ENDPOINT`。Compose 部署修改根目录 `.env` 后，在仓库根目录执行 `docker compose up -d --wait`，无需重新构建镜像。
- **Backend 状态为 unhealthy**：访问 `/actuator/health`，并查看 Backend、MySQL 和 Redis 日志。
- **接口 HTTP 200 但操作失败**：检查响应体的 `code` 和 `msg`，不要只看 HTTP 状态码。
- **Docker 无法连接 daemon**：确认 Docker 服务已启动，且当前用户有访问 Docker 的权限。
