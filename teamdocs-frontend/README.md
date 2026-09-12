# TeamDocs Frontend

TeamDocs 前端：Vue 3 + Vite 6 + Element Plus，飞书风格文档工作区。提供登录、空间列表、文件夹/文档管理、标签管理、评论、最近浏览、活动流与基于 file-viewer 的文档在线预览。

> 接口边界与实现契约见 [`docs/frontend/DECISIONS.md`](../docs/frontend/DECISIONS.md)。

## 技术栈

- Vue 3（Composition API + `<script setup>`）+ Vue Router + Pinia
- Element Plus + lucide-vue-next
- Axios（统一响应拦截 `{ code, msg, data }`）
- @file-viewer 系列渲染器（图片/文本/PDF/Word/表格/演示文稿/OFD）

## 快速开始

前置：Node.js 20+，后端已启动（本机运行和 Compose 的默认端口均为 `8080`）。以下 npm 命令在 `teamdocs-frontend` 目录执行。

```bash
npm ci
npm run dev
```

- 开发地址：`http://localhost:5173`
- API 代理：`/api` 前缀请求由 Vite 转发到 `http://localhost:8080`（见 `vite.config.js` 的 `server.proxy`）
- 后端不是 8080 时，只改 `vite.config.js` 的 proxy target，不要在业务代码里写死环境

## 构建与部署

```bash
npm run build      # 产物输出 dist/
npm run preview    # 本地预览构建产物，默认端口 4173
```

正式部署使用仓库根目录 `docker-compose.yaml` 中的 GHCR 镜像，前端镜像已包含 Nginx 和构建产物，`/api` 转发至容器内的 `backend:8080`。先按 [部署文档](../docs/DEPLOYMENT.md) 准备根目录 `.env`，然后在仓库根目录执行：

```bash
docker compose pull
docker compose up -d --wait
```

默认 Web 入口为 `http://localhost:15173`。域名、TLS、FRP 和 MinIO 文件访问入口的配置统一见部署文档。`npm run preview` 仅用于本地检查构建产物。

通过自定义域名访问 Vite dev/preview 时，如果出现 `allowedHosts` 错误，将域名加入 `vite.config.js` 的 `server.allowedHosts`；preview 默认继承该配置。

## 在线预览

- 预览入口：文档列表中打开 `GET /spaces/{spaceId}/documents/{documentId}/preview` 返回的 `url`（MinIO inline 预签名地址），由 file-viewer 渲染
- 支持图片、文本、PDF、Word、表格、演示文稿与 OFD
- **跨域前提**：浏览器直接跨域加载 MinIO 预签名 URL，MinIO 的 CORS 必须允许前端实际来源。Compose 部署在根目录 `.env` 设置 `MINIO_CORS_ALLOWED_ORIGIN`，值应与浏览器地址栏中的 `scheme://host[:port]` 完全一致，再在仓库根目录执行 `docker compose up -d --wait` 应用配置。本地 Vite dev 默认来源是 `http://localhost:5173`，preview 默认是 `http://localhost:4173`。CORS 响应头由 MinIO 返回，Nginx 不要重复添加
- 预览渲染是纯前端能力，文件本体不解析；大文件加载速度取决于网络与 MinIO 带宽

## 目录结构

```
src/
├── api/           Axios 接口封装（按模块：user/space/document/tag/...）
├── assets/        样式与图标资源
├── components/    通用组件
├── composables/   组合式函数
├── layouts/       布局壳
├── router/        路由与登录守卫
├── stores/        Pinia
├── utils/         工具函数（normalize、格式化等）
├── views/         页面
├── App.vue
└── main.js
```

## 常见问题

- **接口报 401 循环**：token 失效或已注销，Axios 拦截器会清理本地 token 并跳转登录页
- **业务失败但 HTTP 200**：看响应体 `code`（`0` 为失败）与 `msg`，页面提示以拦截器 + 后端 `msg` 为准
- **预览空白/跨域错误**：检查 MinIO 的 `MINIO_CORS_ALLOWED_ORIGIN` 配置与当前页面来源是否完全一致（包括端口），并确认 `MINIO_PUBLIC_ENDPOINT` 指向浏览器可访问的 S3 API。修改 Compose `.env` 后，在仓库根目录执行 `docker compose up -d --wait`
- **`allowedHosts` 错误**：通过自定义域名访问 dev/preview 服务时，把域名加入 `vite.config.js` 的 `server.allowedHosts`
- **上传失败**：确认字段名是 `file`、`FormData` 不要手写 `Content-Type`；容器上传上限 100MB
