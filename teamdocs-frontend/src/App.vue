<template>
  <router-view />
</template>

<script setup>
</script>

<style>
html, body {
  margin: 0;
  padding: 0;
  height: 100%;
  width: 100%;
  font-family: 'Inter', system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  -webkit-font-smoothing: antialiased;
}

#app {
  min-height: 100vh;
  width: 100%;
}

/* ===== 全局动效系统 =====
 * 令牌：全站动画只允许下面两条缓动、三档时长。
 * 硬性约束：动画只动 transform 和 opacity。
 */
:root {
  --ease-out-expo: cubic-bezier(0.16, 1, 0.3, 1); /* 入场、展开 */
  --ease-standard: cubic-bezier(0.4, 0, 0.2, 1);  /* hover 等状态过渡 */
  --dur-fast: 120ms; /* hover / 按下 */
  --dur-mid: 240ms;  /* 弹层 */
  --dur-slow: 320ms; /* 页面转场、区块入场 */
}

/* ===== 全局主题令牌 =====
 * 登录页/用户菜单选的主题写到 html[data-theme]，应用内表面色走这组变量。
 * night/cyberpunk 同时挂 html.dark 让 Element Plus 暗色变量生效。
 * 实体色 (标签色/文件类型徽章/角色徽章) 是数据语义色，不随主题走。
 */
:root,
:root[data-theme='day'] {
  --app-bg: #f7f8fa;
  --app-panel: #ffffff;
  --app-panel-soft: #fbfbfc;
  --app-border: #e8eaed;
  --app-border-soft: #eef0f3;
  --app-hover: #f1f5f9;
  --app-hover-soft: #f8fafc;
  --app-text: #0f172a;
  --app-text-2: #334155;
  --app-text-muted: #64748b;
  --app-text-faint: #94a3b8;
  --app-accent: #2563eb;
  --app-accent-weak: #e8efff;
  --app-highlight: #fef9e7;
  --app-highlight-strong: #fdf3d0;
}

:root[data-theme='night'] {
  --app-bg: #0b0f19;
  --app-panel: #111827;
  --app-panel-soft: #0d1322;
  --app-border: #1f2937;
  --app-border-soft: #1a2332;
  --app-hover: #1f2937;
  --app-hover-soft: #161e2e;
  --app-text: #f9fafb;
  --app-text-2: #e5e7eb;
  --app-text-muted: #9ca3af;
  --app-text-faint: #6b7280;
  --app-accent: #60a5fa;
  --app-accent-weak: rgba(96, 165, 250, 0.16);
  --app-highlight: rgba(250, 204, 21, 0.14);
  --app-highlight-strong: rgba(250, 204, 21, 0.22);
}

:root[data-theme='coffee'] {
  --app-bg: #fffbeb;
  --app-panel: #fffdf5;
  --app-panel-soft: #fef9ec;
  --app-border: #f0e2c0;
  --app-border-soft: #f6ecd4;
  --app-hover: #fef3c7;
  --app-hover-soft: #fdf7e3;
  --app-text: #78350f;
  --app-text-2: #92400e;
  --app-text-muted: #b45309;
  --app-text-faint: #c9954f;
  --app-accent: #b45309;
  --app-accent-weak: #fde68a;
  --app-highlight: #fef3c7;
  --app-highlight-strong: #fde68a;
}

:root[data-theme='sakura'] {
  --app-bg: #fdf2f8;
  --app-panel: #fffafc;
  --app-panel-soft: #fdf5f9;
  --app-border: #f8dcea;
  --app-border-soft: #fbe8f1;
  --app-hover: #fce7f3;
  --app-hover-soft: #fdf0f7;
  --app-text: #831843;
  --app-text-2: #9d174d;
  --app-text-muted: #be185d;
  --app-text-faint: #d67ba6;
  --app-accent: #db2777;
  --app-accent-weak: #fce7f3;
  --app-highlight: #fef9e7;
  --app-highlight-strong: #fdf3d0;
}

:root[data-theme='cyberpunk'] {
  --app-bg: #0c1117;
  --app-panel: #010409;
  --app-panel-soft: #0a0f14;
  --app-border: rgba(74, 222, 128, 0.28);
  --app-border-soft: rgba(74, 222, 128, 0.16);
  --app-hover: rgba(74, 222, 128, 0.1);
  --app-hover-soft: rgba(74, 222, 128, 0.06);
  --app-text: #4ade80;
  --app-text-2: #86efac;
  --app-text-muted: #22c55e;
  --app-text-faint: #15803d;
  --app-accent: #22d3ee;
  --app-accent-weak: rgba(34, 211, 238, 0.14);
  --app-highlight: rgba(34, 211, 238, 0.12);
  --app-highlight-strong: rgba(34, 211, 238, 0.2);
}

/* 路由页面转场：旧页快速淡出，新页自下顺滑升入 */
.page-enter-active {
  transition: opacity var(--dur-slow) var(--ease-out-expo),
              transform var(--dur-slow) var(--ease-out-expo);
}

.page-leave-active {
  transition: opacity var(--dur-fast) ease;
}

.page-enter-from {
  opacity: 0;
  transform: translateY(16px);
}

.page-leave-to {
  opacity: 0;
}

/* 页内区块错峰入场：挂 .anim-item 并用 --delay 控制次序 (60ms 步进) */
.anim-item {
  opacity: 0;
  animation: rise-in 0.4s var(--ease-out-expo) forwards;
  animation-delay: calc(var(--delay, 0) * 60ms);
}

/* 终态必须是 transform: none——残留 translate 会成为 fixed 弹层的 containing block，遮罩会失效 */
@keyframes rise-in {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* 表格/列表行逐行浮入：25ms 步进，第 8 行起不再递增 (总时长 < 0.8s) */
.stagger-rows .el-table__row,
.stagger-rows .recent-row,
.stagger-rows .trash-row {
  opacity: 0;
  animation: row-in 0.3s var(--ease-out-expo) forwards;
}

.stagger-rows .el-table__row:nth-child(1),
.stagger-rows .recent-row:nth-child(1),
.stagger-rows .trash-row:nth-child(1) { animation-delay: 25ms; }
.stagger-rows .el-table__row:nth-child(2),
.stagger-rows .recent-row:nth-child(2),
.stagger-rows .trash-row:nth-child(2) { animation-delay: 50ms; }
.stagger-rows .el-table__row:nth-child(3),
.stagger-rows .recent-row:nth-child(3),
.stagger-rows .trash-row:nth-child(3) { animation-delay: 75ms; }
.stagger-rows .el-table__row:nth-child(4),
.stagger-rows .recent-row:nth-child(4),
.stagger-rows .trash-row:nth-child(4) { animation-delay: 100ms; }
.stagger-rows .el-table__row:nth-child(5),
.stagger-rows .recent-row:nth-child(5),
.stagger-rows .trash-row:nth-child(5) { animation-delay: 125ms; }
.stagger-rows .el-table__row:nth-child(6),
.stagger-rows .recent-row:nth-child(6),
.stagger-rows .trash-row:nth-child(6) { animation-delay: 150ms; }
.stagger-rows .el-table__row:nth-child(7),
.stagger-rows .recent-row:nth-child(7),
.stagger-rows .trash-row:nth-child(7) { animation-delay: 175ms; }
.stagger-rows .el-table__row:nth-child(n+8),
.stagger-rows .recent-row:nth-child(n+8),
.stagger-rows .trash-row:nth-child(n+8) { animation-delay: 175ms; }

@keyframes row-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* Lucide 图标适配：跟随 el-icon 的 font-size (Lucide 默认固定 24px，需归一到 1em) */
.el-icon svg {
  width: 1em;
  height: 1em;
}

/* ===== Element Plus 弹层统一动画 ===== */

/* 遮罩底色统一 (弹层都已 append-to-body 挂 body 下，避开页面内层叠上下文) */
.el-overlay {
  background-color: rgba(15, 23, 42, 0.45);
}

/* 对话框：打开 240ms 放大浮现，关闭 150ms 反向；遮罩 200ms 淡入 */
.dialog-fade-enter-active {
  animation: overlay-fade-in 200ms var(--ease-standard);
}

.dialog-fade-enter-active .el-overlay-dialog {
  animation: dialog-pop-in var(--dur-mid) var(--ease-out-expo);
}

.dialog-fade-leave-active {
  animation: overlay-fade-in 150ms var(--ease-standard) reverse;
}

.dialog-fade-leave-active .el-overlay-dialog {
  animation: dialog-pop-in 150ms var(--ease-standard) reverse;
}

@keyframes overlay-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes dialog-pop-in {
  from {
    opacity: 0;
    transform: scale(0.96) translateY(8px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* 抽屉：打开 280ms 从右滑入，关闭 200ms (略快) */
.el-drawer.rtl {
  animation: drawer-slide-in 280ms var(--ease-out-expo);
}

.el-drawer-fade-enter-active {
  transition: opacity 200ms var(--ease-standard);
}

.el-drawer-fade-leave-active {
  transition: opacity 200ms var(--ease-standard);
}

.el-drawer-fade-enter-from,
.el-drawer-fade-leave-to {
  opacity: 0;
}

@keyframes drawer-slide-in {
  from { transform: translateX(100%); }
  to { transform: none; }
}

/* 下拉菜单 / select 弹层：160ms 顶部为原点轻微放大 */
.el-zoom-in-top-enter-active,
.el-zoom-in-top-leave-active {
  transition: opacity 160ms var(--ease-out-expo),
              transform 160ms var(--ease-out-expo) !important;
  transform-origin: center top !important;
}

.el-zoom-in-top-enter-from,
.el-zoom-in-top-leave-to {
  opacity: 0;
  transform: scale(0.96);
}

/* ===== 悬停与点击微反馈 ===== */

/* 主按钮按下轻缩 */
.el-button:active {
  transform: scale(0.97);
  transition: transform 80ms var(--ease-standard);
}

/* ===== 移动端弹层全宽 =====
 * 所有 el-dialog 固定 width 在窄屏统一压到 92vw，抽屉压到 86vw，
 * 弹层都 append-to-body，这里全局覆盖即可，不用逐组件写。
 */
@media (max-width: 768px) {
  .el-dialog {
    width: 92vw !important;
    max-width: 92vw;
  }

  .el-drawer {
    width: 86vw !important;
  }

  .el-message-box {
    width: 86vw !important;
    max-width: 86vw;
  }
}
</style>
