<template>
  <div class="landing">
    <!-- ===== 固定顶栏 ===== -->
    <header class="lp-nav">
      <div class="lp-nav-inner">
        <span class="lp-logo">TeamDocs</span>
        <div class="lp-nav-actions">
          <button type="button" class="lp-nav-login" @click="router.push('/login')">登录</button>
          <button type="button" class="lp-pill-btn" :disabled="demoLoading" @click="handleDemo">
            {{ demoLoading ? '进入中…' : '体验演示' }}
          </button>
        </div>
      </div>
    </header>

    <!-- ===== Hero ===== -->
    <section class="lp-hero">
      <!-- 两侧同心弧线 (桌面端) -->
      <div class="lp-lines lp-lines-left" aria-hidden="true">
        <span
          v-for="i in 20"
          :key="'l' + i"
          class="lp-line lp-line-left"
          :style="lineStyle(i)"
        ></span>
      </div>
      <div class="lp-lines lp-lines-right" aria-hidden="true">
        <span
          v-for="i in 20"
          :key="'r' + i"
          class="lp-line lp-line-right"
          :style="lineStyle(i)"
        ></span>
      </div>
      <!-- 顶部横向弧线 (移动端) -->
      <div class="lp-lines-top" aria-hidden="true">
        <span
          v-for="i in 10"
          :key="'t' + i"
          class="lp-line lp-line-top"
          :style="topLineStyle(i)"
        ></span>
      </div>

      <!-- 功能跑马灯 -->
      <div class="lp-ticker">
        <div class="lp-ticker-track">
          <template v-for="n in 4" :key="n">
            <span v-for="item in tickerItems" :key="n + item" class="lp-ticker-item">
              {{ item }}
            </span>
          </template>
        </div>
      </div>

      <!-- 大标题：serif 斜体品牌词独占中行，三行固定版式 -->
      <h1 class="lp-title">
        团队文档交给<br /><span class="lp-serif">teamdocs</span><br />井然有序。
      </h1>

      <p class="lp-subtitle">
        空间隔离、文件夹层级、标签分类、全文搜索与评论协作，为团队知识建立秩序。
      </p>

      <!-- CTA -->
      <div class="lp-cta-row">
        <button type="button" class="lp-cta-primary" :disabled="demoLoading" @click="handleDemo">
          {{ demoLoading ? '正在进入演示…' : '一键体验演示' }}
        </button>
        <button type="button" class="lp-cta-book" @click="router.push('/login')">
          <span class="lp-book-avatar">
            <User :size="18" :stroke-width="2.2" />
          </span>
          <span class="lp-book-text">
            <span class="lp-book-primary">登录 / 注册</span>
            <span class="lp-book-secondary">
              <span class="lp-green-dot"></span>
              创建你的团队空间
            </span>
          </span>
        </button>
      </div>

      <!-- 底部渐白过渡 -->
      <div class="lp-fade-bottom" aria-hidden="true"></div>
    </section>

    <!-- ===== 技术栈跑马灯 ===== -->
    <section class="lp-trusted reveal">
      <p class="lp-trusted-label">基于主流工程技术栈构建</p>
      <div class="lp-trusted-marquee">
        <div class="lp-trusted-track">
          <template v-for="n in 4" :key="n">
            <span
              v-for="tech in techLogos"
              :key="n + tech.name"
              class="lp-tech-logo"
              :style="{ fontFamily: tech.family, fontWeight: tech.weight }"
            >
              {{ tech.name }}
            </span>
          </template>
        </div>
      </div>
    </section>

    <!-- ===== 功能特性 ===== -->
    <section class="lp-features">
      <div class="lp-section-head reveal">
        <h2 class="lp-section-title">
          协作所需的一切，<span class="lp-serif">井然</span>就位。
        </h2>
        <p class="lp-section-sub">从上传第一份文档到团队规模化协作，每个环节都有秩序。</p>
      </div>

      <div class="lp-feature-grid">
        <div
          v-for="(f, i) in features"
          :key="f.title"
          class="lp-feature-card reveal"
          :style="{ transitionDelay: `${(i % 3) * 80}ms` }"
        >
          <div class="lp-feature-icon">
            <component :is="f.icon" :size="22" :stroke-width="1.9" />
          </div>
          <h3 class="lp-feature-title">{{ f.title }}</h3>
          <p class="lp-feature-desc">{{ f.desc }}</p>
        </div>
      </div>
    </section>

    <!-- ===== 工程亮点 ===== -->
    <section class="lp-craft">
      <div class="lp-craft-inner">
        <div class="lp-craft-text reveal">
          <h2 class="lp-section-title">
            不止能用，<br />更<span class="lp-serif">经得起追问</span>。
          </h2>
          <p class="lp-section-sub">
            每个技术决策都有取舍记录：为什么选 MySQL 全文索引而不是
            Elasticsearch，为什么缓存只做空间详情，注解式权限切面怎么防越权。
          </p>
          <ul class="lp-craft-list">
            <li v-for="item in craftItems" :key="item">
              <Check :size="16" :stroke-width="2.5" class="lp-craft-check" />
              {{ item }}
            </li>
          </ul>
        </div>

        <div class="lp-craft-stats reveal" style="transition-delay: 120ms">
          <div v-for="s in stats" :key="s.label" class="lp-stat">
            <span class="lp-stat-value">{{ s.value }}</span>
            <span class="lp-stat-label">{{ s.label }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ===== 底部大 CTA ===== -->
    <section class="lp-final">
      <div class="lp-final-inner reveal">
        <h2 class="lp-final-title">
          现在就试试 <span class="lp-serif lp-final-serif">teamdocs</span>
        </h2>
        <p class="lp-final-sub">无需注册，演示账号一键进入完整工作台。</p>
        <button type="button" class="lp-final-btn" :disabled="demoLoading" @click="handleDemo">
          {{ demoLoading ? '正在进入演示…' : '一键体验演示' }}
        </button>
      </div>
    </section>

    <footer class="lp-footer">
      <span>© 2026 TeamDocs · 团队文档协作平台</span>
    </footer>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  User,
  Check,
  LayoutGrid,
  FolderTree,
  Tags,
  Search,
  MessageSquare,
  ShieldCheck
} from 'lucide-vue-next'
import { loginApi } from '@/api/user'

const router = useRouter()
const demoLoading = ref(false)

const tickerItems = ['空间协作', '文档管理', '标签分类', '全文搜索', '评论讨论', '回收站', '权限管理', '操作日志']

const features = [
  { icon: LayoutGrid, title: '空间隔离', desc: '每个团队独立空间，Owner / Admin / Member 三级角色，注解式切面统一鉴权。' },
  { icon: FolderTree, title: '文件夹层级', desc: '无限层级目录树，移动防环校验，删除级联进回收站可随时恢复。' },
  { icon: Tags, title: '标签分类', desc: '空间内多对多打标，按标签一键筛选，同名标签全站同色。' },
  { icon: Search, title: '全文搜索', desc: 'MySQL FULLTEXT + ngram 中文分词，文档名与标签一次命中。' },
  { icon: MessageSquare, title: '评论协作', desc: '文档级评论与回复，删除保留占位不断上下文。' },
  { icon: ShieldCheck, title: '安全边界', desc: 'JWT 双撤销机制：jti 黑名单 + 用户级时间水位，改密全端下线。' }
]

const craftItems = [
  '68 个单元测试覆盖权限、分页与生命周期',
  'Redis 缓存与 Lua 原子限流',
  'MinIO 公私双桶与预签名 URL',
  'AOP 操作日志独立事务失败隔离',
  'Docker Compose 一键启动全栈'
]

const stats = [
  { value: '40+', label: 'REST 接口' },
  { value: '68', label: '单元测试' },
  { value: '10+', label: '核心模块' },
  { value: '1', label: '命令启动' }
]

// 技术栈文字 logo：各用不同字体拟合“客户 logo 墙”的质感
const techLogos = [
  { name: 'Spring Boot', family: 'Georgia, serif', weight: 700 },
  { name: 'MySQL', family: 'system-ui, sans-serif', weight: 800 },
  { name: 'Redis', family: 'Georgia, serif', weight: 500 },
  { name: 'MinIO', family: 'Inter, system-ui, sans-serif', weight: 600 },
  { name: 'Vue 3', family: 'system-ui, sans-serif', weight: 700 },
  { name: 'Pinia', family: 'Georgia, serif', weight: 600 },
  { name: 'MyBatis Plus', family: 'Inter, system-ui, sans-serif', weight: 700 },
  { name: 'Docker', family: 'system-ui, sans-serif', weight: 800 },
  { name: 'Spring Security', family: 'Inter, system-ui, sans-serif', weight: 600 }
]

// 同心弧线：宽 60px 起每条 +10px，错峰 0.25s
function lineStyle(i) {
  return {
    width: `${60 + i * 10}px`,
    height: `${140 + i * 26}px`,
    animationDelay: `${i * 0.25}s`
  }
}

function topLineStyle(i) {
  return {
    height: `${40 + i * 8}px`,
    width: `${120 + i * 32}px`,
    animationDelay: `${i * 0.25}s`
  }
}

// 滚动进入视口时揭示 (.reveal -> .is-visible)，一次性触发后停止观察
let revealObserver = null

onMounted(() => {
  revealObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible')
          revealObserver.unobserve(entry.target)
        }
      })
    },
    { threshold: 0.15 }
  )
  document.querySelectorAll('.landing .reveal').forEach((el) => revealObserver.observe(el))
})

onBeforeUnmount(() => {
  revealObserver?.disconnect()
})

// 一键体验：演示账号直接登录进工作台
async function handleDemo() {
  demoLoading.value = true
  try {
    const res = await loginApi({ username: 'demo', password: 'demo123456' })
    const token = typeof res === 'string' ? res : res?.token
    if (token) {
      localStorage.setItem('teamdocs_token', token)
      ElMessage.success('已进入演示账号')
      router.push('/home')
    }
  } catch (err) {
    // 拦截器提示
  } finally {
    demoLoading.value = false
  }
}
</script>

<style scoped>
/* ===== 设计令牌 ===== */
.landing {
  --bg: #ffffff;
  --text: #0a0a0a;
  --muted: #6b6b6b;
  --button-bg: #0a0a0a;
  --button-text: #ffffff;
  --border-soft: rgba(0, 0, 0, 0.08);
  --green: #1dcc5d;
  --font-sans: 'Inter', system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  --font-serif: Georgia, 'Times New Roman', 'Songti SC', SimSun, serif;

  min-height: 100vh;
  background: var(--bg);
  color: var(--text);
  font-family: var(--font-sans);
  overflow-x: hidden;
}

/* ===== 顶栏 ===== */
.lp-nav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(12px);
}

.lp-nav-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 19px 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.lp-logo {
  font-family: var(--font-serif);
  font-size: 30px;
  font-weight: 600;
  font-style: italic;
  letter-spacing: -0.08em;
  user-select: none;
}

.lp-nav-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.lp-nav-login {
  border: none;
  background: none;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
  padding: 10px 16px;
  border-radius: 999px;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.lp-nav-login:hover {
  background: rgba(0, 0, 0, 0.05);
}

.lp-pill-btn {
  border: none;
  background: var(--button-bg);
  color: var(--button-text);
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  padding: 10px 20px;
  border-radius: 999px;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.lp-pill-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.12);
}

.lp-pill-btn:disabled {
  opacity: 0.6;
  cursor: default;
  transform: none;
}

/* ===== Hero ===== */
.lp-hero {
  position: relative;
  min-height: min(680px, calc(100vh - 140px));
  padding: 120px 36px 90px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  overflow: hidden;
}

/* 两侧同心弧线 */
.lp-lines {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 320px;
  pointer-events: none;
}

.lp-lines-left { left: 0; }
.lp-lines-right { right: 0; }

.lp-line {
  position: absolute;
  top: 50%;
  opacity: 0;
  border: 2.5px solid var(--border-soft);
  animation: line-pulse 5s ease-in-out infinite;
}

.lp-line-left {
  left: 0;
  transform: translateY(-50%);
  border-left: none;
  border-radius: 0 100% 100% 0 / 0 50% 50% 0;
}

.lp-line-right {
  right: 0;
  transform: translateY(-50%);
  border-right: none;
  border-radius: 100% 0 0 100% / 50% 0 0 50%;
}

/* 移动端顶部横向弧线 */
.lp-lines-top {
  display: none;
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 200px;
  pointer-events: none;
}

.lp-line-top {
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  opacity: 0;
  border: 2.5px solid var(--border-soft);
  border-top: none;
  border-radius: 0 0 100% 100% / 0 0 80% 80%;
  animation: line-pulse 5s ease-in-out infinite;
}

@keyframes line-pulse {
  0% { opacity: 0; }
  15% { opacity: 0.9; }
  70% { opacity: 0.4; }
  100% { opacity: 0; }
}

/* 功能跑马灯 */
.lp-ticker {
  position: relative;
  z-index: 2;
  max-width: 500px;
  width: 100%;
  height: 36px;
  overflow: hidden;
  margin-bottom: 28px;
  -webkit-mask-image: linear-gradient(90deg, transparent 0%, black 12%, black 88%, transparent 100%);
  mask-image: linear-gradient(90deg, transparent 0%, black 12%, black 88%, transparent 100%);
}

.lp-ticker-track {
  display: flex;
  align-items: center;
  gap: 8px;
  width: max-content;
  animation: marquee-left 30s linear infinite;
}

.lp-ticker-item {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--muted);
  padding: 6px 14px;
  border-radius: 999px;
  background: rgb(251, 251, 251);
  white-space: nowrap;
}

@keyframes marquee-left {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}

/* 大标题 */
.lp-title {
  position: relative;
  z-index: 2;
  max-width: 680px;
  margin: 0 0 22px;
  font-size: clamp(40px, 5.5vw, 64px);
  line-height: 1.1;
  font-weight: 600;
  letter-spacing: -0.045em;
}

.lp-serif {
  font-family: var(--font-serif);
  font-style: italic;
  font-weight: 600;
  letter-spacing: -0.05em;
}

/* 副标题 */
.lp-subtitle {
  position: relative;
  z-index: 2;
  max-width: 520px;
  margin: 0 0 34px;
  font-size: 16px;
  line-height: 1.55;
  font-weight: 400;
  color: var(--muted);
}

/* CTA */
.lp-cta-row {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 16px;
}

.lp-cta-primary {
  height: 56px;
  padding: 18px 30px;
  border: none;
  border-radius: 999px;
  background: var(--button-bg);
  color: var(--button-text);
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.lp-cta-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.12);
}

.lp-cta-primary:disabled {
  opacity: 0.65;
  cursor: default;
  transform: none;
  box-shadow: none;
}

.lp-cta-book {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 24px 8px 8px;
  background: #ffffff;
  border: 4px solid rgb(248, 248, 248);
  border-radius: 999px;
  cursor: pointer;
  font-family: var(--font-sans);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.lp-cta-book:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 18px rgba(0, 0, 0, 0.08);
}

.lp-book-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.lp-book-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
}

.lp-book-primary {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.lp-book-secondary {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 500;
  color: rgb(152, 152, 152);
}

.lp-green-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--green);
}

/* 底部渐白 */
.lp-fade-bottom {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 178px;
  background: linear-gradient(180deg, transparent 0%, rgba(255, 255, 255, 0.4) 40%, #ffffff 100%);
  pointer-events: none;
}

/* ===== 技术栈跑马灯 ===== */
.lp-trusted {
  max-width: 1200px;
  margin: 0 auto;
  padding: 36px;
  display: flex;
  align-items: center;
  gap: 40px;
}

.lp-trusted-label {
  max-width: 180px;
  flex-shrink: 0;
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.5;
  color: var(--muted);
}

.lp-trusted-marquee {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  -webkit-mask-image: linear-gradient(90deg, transparent 0%, black 12%, black 88%, transparent 100%);
  mask-image: linear-gradient(90deg, transparent 0%, black 12%, black 88%, transparent 100%);
}

.lp-trusted-track {
  display: flex;
  align-items: center;
  gap: 48px;
  width: max-content;
  animation: marquee-left 30s linear infinite;
}

.lp-tech-logo {
  flex-shrink: 0;
  font-size: 16px;
  color: var(--text);
  white-space: nowrap;
}

/* ===== 滚动揭示 ===== */
.reveal {
  opacity: 0;
  transform: translateY(28px);
  transition: opacity 0.7s cubic-bezier(0.16, 1, 0.3, 1),
              transform 0.7s cubic-bezier(0.16, 1, 0.3, 1);
}

.reveal.is-visible {
  opacity: 1;
  transform: none;
}

/* ===== 功能特性 ===== */
.lp-features {
  max-width: 1200px;
  margin: 0 auto;
  padding: 90px 36px;
}

.lp-section-head {
  text-align: center;
  margin-bottom: 56px;
}

.lp-section-title {
  margin: 0 0 14px;
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 600;
  letter-spacing: -0.045em;
  line-height: 1.15;
}

.lp-section-sub {
  margin: 0;
  font-size: 16px;
  line-height: 1.6;
  color: var(--muted);
}

.lp-feature-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.lp-feature-card {
  border: 1px solid var(--border-soft);
  border-radius: 18px;
  padding: 28px 26px;
  background: #fff;
  transition: opacity 0.7s cubic-bezier(0.16, 1, 0.3, 1),
              transform 0.7s cubic-bezier(0.16, 1, 0.3, 1),
              box-shadow 0.2s ease,
              border-color 0.2s ease;
}

.lp-feature-card:hover {
  border-color: rgba(0, 0, 0, 0.16);
  box-shadow: 0 16px 40px -18px rgba(0, 0, 0, 0.14);
}

.lp-feature-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: rgb(248, 248, 248);
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 18px;
}

.lp-feature-title {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.lp-feature-desc {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--muted);
}

/* ===== 工程亮点 ===== */
.lp-craft {
  border-top: 1px solid var(--border-soft);
  border-bottom: 1px solid var(--border-soft);
  background: rgb(252, 252, 252);
}

.lp-craft-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 90px 36px;
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 64px;
  align-items: center;
}

.lp-craft-text .lp-section-title,
.lp-craft-text .lp-section-sub {
  text-align: left;
}

.lp-craft-text .lp-section-sub {
  margin-bottom: 26px;
}

.lp-craft-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lp-craft-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14.5px;
  font-weight: 500;
}

.lp-craft-check {
  color: var(--green);
  flex-shrink: 0;
}

.lp-craft-stats {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 18px;
}

.lp-stat {
  border: 1px solid var(--border-soft);
  border-radius: 18px;
  background: #fff;
  padding: 30px 24px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.lp-stat-value {
  font-family: var(--font-serif);
  font-style: italic;
  font-size: 42px;
  font-weight: 600;
  letter-spacing: -0.05em;
  line-height: 1;
}

.lp-stat-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--muted);
}

/* ===== 底部大 CTA ===== */
.lp-final {
  padding: 110px 36px;
  text-align: center;
}

.lp-final-title {
  margin: 0 0 12px;
  font-size: clamp(34px, 5vw, 56px);
  font-weight: 600;
  letter-spacing: -0.05em;
}

.lp-final-serif {
  letter-spacing: -0.06em;
}

.lp-final-sub {
  margin: 0 0 30px;
  font-size: 16px;
  color: var(--muted);
}

.lp-final-btn {
  height: 56px;
  padding: 18px 36px;
  border: none;
  border-radius: 999px;
  background: var(--button-bg);
  color: var(--button-text);
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.lp-final-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.14);
}

.lp-final-btn:disabled {
  opacity: 0.65;
  cursor: default;
  transform: none;
  box-shadow: none;
}

/* ===== 页脚 ===== */
.lp-footer {
  padding: 24px 36px 32px;
  border-top: 1px solid var(--border-soft);
  text-align: center;
  font-size: 12.5px;
  color: rgb(170, 170, 170);
}

/* ===== 响应式 ===== */
@media (max-width: 1200px) {
  .lp-hero { padding: 140px 32px 100px; }
  .lp-nav-inner { padding: 19px 32px; }
}

@media (max-width: 810px) {
  .lp-hero {
    min-height: 760px;
    padding: 120px 24px 96px;
  }
  .lp-lines { display: none; }
  .lp-lines-top { display: block; }
  .lp-title { font-size: clamp(38px, 11vw, 52px); }
  .lp-cta-row {
    flex-direction: column;
    width: 100%;
    max-width: 320px;
  }
  .lp-cta-primary,
  .lp-cta-book {
    width: 100%;
    justify-content: center;
  }
  .lp-trusted {
    flex-direction: column;
    align-items: flex-start;
    gap: 20px;
  }
  .lp-trusted-label { max-width: none; }
  .lp-nav-inner { padding: 14px 20px; }
  .lp-logo { font-size: 26px; }
  .lp-feature-grid { grid-template-columns: 1fr; }
  .lp-craft-inner {
    grid-template-columns: 1fr;
    gap: 40px;
    padding: 64px 24px;
  }
  .lp-features { padding: 64px 24px; }
  .lp-final { padding: 80px 24px; }
}
</style>
