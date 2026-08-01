# TeamDocs Frontend

TeamDocs 前端：Vue 3 + Vite 6 + Element Plus，飞书风格文档工作区。提供登录、空间列表、文件夹/文档管理、标签管理、评论、最近浏览、活动流与基于 file-viewer 的文档在线预览。

> 接口边界与实现契约见 [`docs/frontend/DECISIONS.md`](../docs/frontend/DECISIONS.md)。

## 技术栈

- Vue 3（Composition API + `<script setup>`）+ Vue Router + Pinia
- Element Plus + lucide-vue-next
- Axios（统一响应拦截 `{ code, msg, data }`）
- @file-viewer 系列渲染器（图片/文本/PDF/Word/表格/演示文稿/OFD）

## 快速开始

前置：后端已启动（原生 `8080` 或 Docker `18080`）。

```powershell
npm install
npm run dev
```

- 开发地址：`http://localhost:5173`
- API 代理：`/api` 前缀请求由 Vite 转发到 `http://localhost:8080`（见 `vite.config.js` 的 `server.proxy`）
- 后端不是 8080 时，只改 `vite.config.js` 的 proxy target，不要在业务代码里写死环境

## 构建与部署

```powershell
npm run build      # 产物输出 dist/
npm run preview    # 本地预览构建产物，端口 5173
```

- 部署到服务器时，用 Nginx 托管 `dist/`，把 `/api` 反向代理到后端（Compose 部署时是 `BACKEND_PORT`），把文件访问域名代理到 MinIO
- 通过域名访问时，如果 Vite dev/preview 报 `allowedHosts` 错误，把域名加入 `vite.config.js` 的 `server.allowedHosts` 列表

## 在线预览

- 预览入口：文档列表中打开 `GET /spaces/{spaceId}/documents/{documentId}/preview` 返回的 `url`（MinIO inline 预签名地址），由 file-viewer 渲染
- 支持图片、文本、PDF、Word、表格、演示文稿与 OFD
- **跨域前提**：浏览器直接跨域加载 MinIO 预签名 URL，所以 MinIO 的 CORS 必须允许前端来源。Docker 部署时在 `.env.docker` 设置 `MINIO_CORS_ALLOWED_ORIGIN`（必须与浏览器地址栏中的 `scheme://host[:port]` 完全一致），并 `--force-recreate minio` 容器；Nginx 反向代理 MinIO 的场景需要同样的 `Access-Control-Allow-Origin` 头
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
- **预览空白/跨域错误**：检查后端 `MINIO_CORS_ALLOWED_ORIGIN` 与当前页面来源是否完全一致（包括端口），修改后重建 MinIO 容器或重载 Nginx
- **`allowedHosts` 错误**：通过自定义域名访问 dev/preview 服务时，把域名加入 `vite.config.js` 的 `server.allowedHosts`
- **上传失败**：确认字段名是 `file`、`FormData` 不要手写 `Content-Type`；容器上传上限 100MB
