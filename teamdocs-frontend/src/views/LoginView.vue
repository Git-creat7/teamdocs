<template>
  <div class="auth-root" :data-theme="currentTheme">
    <!-- 主题切换器 -->
    <div class="theme-bar enter-fade" style="--d: 1.45s">
      <button
        v-for="t in themeList"
        :key="t.key"
        type="button"
        :class="['theme-btn', { active: currentTheme === t.key }]"
        :title="t.name"
        @click="setTheme(t.key)"
      >
        <component :is="t.icon" :size="15" :stroke-width="2.2" />
      </button>
    </div>

    <!-- 居中卡片 -->
    <div class="auth-center">
      <div class="auth-card card-enter">
        <div class="card-head enter-rise" style="--d: 0.35s">
          <span class="brand-line">TEAMDOCS</span>
          <h1 class="card-title">{{ isLogin ? 'LOGIN' : 'SIGNUP' }}</h1>
          <p class="card-desc">{{ isLogin ? '登录你的团队空间' : '创建新账号' }}</p>
        </div>

        <form class="auth-form" @submit.prevent="handleSubmit">
          <div class="field enter-rise" style="--d: 0.65s">
            <label class="field-label" for="username">USERNAME</label>
            <input
              id="username"
              v-model.trim="form.username"
              type="text"
              class="field-input"
              placeholder="2-16 位账号"
              maxlength="16"
              autocomplete="username"
            />
          </div>

          <div class="field enter-rise" style="--d: 0.9s">
            <label class="field-label" for="password">PASSWORD</label>
            <div class="pwd-wrap">
              <input
                id="password"
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                class="field-input"
                placeholder="6-20 位密码"
                maxlength="20"
                :autocomplete="isLogin ? 'current-password' : 'new-password'"
              />
              <button
                type="button"
                class="pwd-toggle"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <EyeOff v-if="showPassword" :size="16" />
                <Eye v-else :size="16" />
              </button>
            </div>
          </div>

          <div v-if="!isLogin" class="field slide-in">
            <label class="field-label" for="confirmPassword">CONFIRM PASSWORD</label>
            <input
              id="confirmPassword"
              v-model="form.confirmPassword"
              type="password"
              class="field-input"
              placeholder="再输入一次密码"
              maxlength="20"
              autocomplete="new-password"
            />
          </div>

          <p v-if="errorMsg" class="form-error">{{ errorMsg }}</p>

          <button type="submit" class="submit-btn enter-rise" style="--d: 1.15s" :disabled="loading">
            <Loader2 v-if="loading" :size="15" class="spin" />
            {{ loading ? 'PROCESSING…' : (isLogin ? 'LOGIN' : 'CREATE ACCOUNT') }}
          </button>
        </form>

        <button type="button" class="switch-btn enter-fade" style="--d: 1.35s" @click="switchMode">
          {{ isLogin ? "还没有账号？SIGNUP" : '已有账号？LOGIN' }}
        </button>
      </div>
    </div>

    <!-- 四角装饰方块 -->
    <div class="deco enter-fade" style="--d: 1.6s" aria-hidden="true">
      <span class="deco-box d1"></span>
      <span class="deco-box d2"></span>
      <span class="deco-box d3"></span>
      <span class="deco-box d4"></span>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff, Sun, Moon, Coffee, Cherry, Zap, Loader2 } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import { loginApi, registerApi } from '@/api/user'

const router = useRouter()

const themeList = [
  { key: 'day', name: 'Day', icon: Sun },
  { key: 'night', name: 'Night', icon: Moon },
  { key: 'coffee', name: 'Coffee', icon: Coffee },
  { key: 'sakura', name: 'Sakura', icon: Cherry },
  { key: 'cyberpunk', name: 'Cyberpunk', icon: Zap }
]

// 全局主题 store：登录页选的主题延续到整个应用
const themeStore = useThemeStore()
const { theme: currentTheme } = storeToRefs(themeStore)
const setTheme = themeStore.setTheme

const isLogin = ref(true)
const showPassword = ref(false)
const loading = ref(false)
const errorMsg = ref('')

const form = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

function switchMode() {
  isLogin.value = !isLogin.value
  form.password = ''
  form.confirmPassword = ''
  errorMsg.value = ''
}

function validate() {
  if (form.username.length < 2 || form.username.length > 16) {
    return '账号长度需在 2-16 位之间'
  }
  if (form.password.length < 6 || form.password.length > 20) {
    return '密码长度需在 6-20 位之间'
  }
  if (!isLogin.value && form.password !== form.confirmPassword) {
    return '两次输入的密码不一致'
  }
  return ''
}

async function handleSubmit() {
  errorMsg.value = validate()
  if (errorMsg.value) return

  loading.value = true
  try {
    if (!isLogin.value) {
      await registerApi({ username: form.username, password: form.password })
      ElMessage.success('注册成功，正在自动登录')
    }
    const res = await loginApi({ username: form.username, password: form.password })
    const token = typeof res === 'string' ? res : res?.token
    if (token) {
      localStorage.setItem('teamdocs_token', token)
      if (isLogin.value) ElMessage.success('登录成功，欢迎回来')
      router.replace('/home')
    } else {
      errorMsg.value = '登录响应异常：未获取到有效 Token'
    }
  } catch (err) {
    // 业务错误 (密码错误/用户名已存在) 拦截器已 toast，这里不重复
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ===== 主题变量：一组变量五套配色，切换全局 0.5s 过渡 ===== */
.auth-root {
  --bg: #ffffff;
  --card-bg: #f9fafb;
  --text: #0a0a0a;
  --accent: #4b5563;
  --border: #0a0a0a;
  --btn-bg: #0a0a0a;
  --btn-text: #ffffff;
  --btn-hover: #1f2937;
  --chip-bg: #e5e7eb;
  --chip-hover: #d1d5db;

  min-height: 100vh;
  background: var(--bg);
  color: var(--text);
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, 'Courier New', monospace;
  transition: background-color 0.5s ease, color 0.5s ease;
  position: relative;
  overflow: hidden;
}

.auth-root[data-theme='night'] {
  --bg: #000000;
  --card-bg: #111827;
  --text: #ffffff;
  --accent: #60a5fa;
  --border: #ffffff;
  --btn-bg: #ffffff;
  --btn-text: #0a0a0a;
  --btn-hover: #e5e7eb;
  --chip-bg: #1f2937;
  --chip-hover: #374151;
}

.auth-root[data-theme='coffee'] {
  --bg: #fffbeb;
  --card-bg: #fef3c7;
  --text: #78350f;
  --accent: #b45309;
  --border: #78350f;
  --btn-bg: #78350f;
  --btn-text: #fffbeb;
  --btn-hover: #92400e;
  --chip-bg: #fde68a;
  --chip-hover: #fcd34d;
}

.auth-root[data-theme='sakura'] {
  --bg: #fdf2f8;
  --card-bg: #fce7f3;
  --text: #831843;
  --accent: #be185d;
  --border: #831843;
  --btn-bg: #831843;
  --btn-text: #fdf2f8;
  --btn-hover: #9d174d;
  --chip-bg: #fbcfe8;
  --chip-hover: #f9a8d4;
}

.auth-root[data-theme='cyberpunk'] {
  --bg: #111827;
  --card-bg: #000000;
  --text: #4ade80;
  --accent: #22d3ee;
  --border: #4ade80;
  --btn-bg: #4ade80;
  --btn-text: #000000;
  --btn-hover: #86efac;
  --chip-bg: #1f2937;
  --chip-hover: #374151;
}

/* ===== 入场动效 =====
   fill 用 backwards：延迟期定格起始帧、播完回归自然态，
   不会像 forwards 那样把 transform 锁死、压掉 hover 缩放 */
.card-enter {
  animation: card-in 0.9s cubic-bezier(0.16, 1, 0.3, 1) backwards;
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(40px) scale(0.96);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.enter-rise {
  animation: rise-in 0.85s cubic-bezier(0.16, 1, 0.3, 1) var(--d, 0s) backwards;
}

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

.enter-fade {
  animation: fade-in 0.9s ease var(--d, 0s) backwards;
}

@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

/* ===== 主题切换器 ===== */
.theme-bar {
  position: absolute;
  top: 16px;
  right: 16px;
  display: flex;
  gap: 8px;
  z-index: 10;
}

.theme-btn {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  background: var(--chip-bg);
  color: var(--text);
  cursor: pointer;
  transition: transform 0.3s ease, background-color 0.3s ease, box-shadow 0.3s ease;
}

.theme-btn:hover {
  transform: scale(1.1);
  background: var(--chip-hover);
}

.theme-btn.active {
  box-shadow: 0 0 0 2px var(--bg), 0 0 0 4px var(--border);
}

/* ===== 卡片 ===== */
.auth-center {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.auth-card {
  width: 100%;
  max-width: 420px;
  background: var(--card-bg);
  border: 2px solid var(--border);
  border-radius: 12px;
  padding: 28px 26px;
  transition: background-color 0.5s ease, border-color 0.5s ease, transform 0.5s ease;
}

.auth-card:hover {
  transform: scale(1.02);
}

.card-head {
  text-align: center;
  margin-bottom: 22px;
}

.brand-line {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.42em;
  color: var(--accent);
  margin-bottom: 10px;
}

.card-title {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.card-desc {
  margin: 0;
  font-size: 13px;
  color: var(--accent);
}

/* ===== 表单 ===== */
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.slide-in {
  animation: slide-in-top 0.3s ease both;
}

@keyframes slide-in-top {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.field-label {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.field-input {
  width: 100%;
  height: 42px;
  padding: 0 12px;
  border: 2px solid var(--border);
  border-radius: 8px;
  background: var(--bg);
  color: var(--text);
  font-family: inherit;
  font-size: 14px;
  outline: none;
  box-sizing: border-box;
  transition: transform 0.3s ease, box-shadow 0.3s ease,
              background-color 0.5s ease, border-color 0.5s ease, color 0.5s ease;
}

.field-input::placeholder {
  color: var(--accent);
  opacity: 0.55;
}

.field-input:focus {
  transform: scale(1.02);
  box-shadow: 0 0 0 2px var(--bg), 0 0 0 4px var(--border);
}

.pwd-wrap {
  position: relative;
}

.pwd-wrap .field-input {
  padding-right: 42px;
}

.pwd-toggle {
  position: absolute;
  right: 4px;
  top: 50%;
  translate: 0 -50%;
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  color: var(--text);
  cursor: pointer;
  border-radius: 6px;
}

.form-error {
  margin: -4px 0 0;
  font-size: 12px;
  font-weight: 700;
  color: #ef4444;
}

.submit-btn {
  height: 44px;
  border: none;
  border-radius: 8px;
  background: var(--btn-bg);
  color: var(--btn-text);
  font-family: inherit;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.1em;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: transform 0.3s ease, background-color 0.3s ease;
}

.submit-btn:hover {
  transform: scale(1.03);
  background: var(--btn-hover);
}

.submit-btn:active {
  transform: scale(0.96);
}

.submit-btn:disabled {
  opacity: 0.65;
  cursor: default;
  transform: none;
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 切换登录/注册 */
.switch-btn {
  display: block;
  margin: 20px auto 0;
  border: none;
  background: none;
  color: var(--text);
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition: transform 0.3s ease;
}

.switch-btn:hover {
  transform: scale(1.05);
  text-decoration: underline;
}

/* ===== 四角装饰方块 ===== */
.deco {
  position: fixed;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}

.deco-box {
  position: absolute;
  border: 2px solid var(--border);
  transition: border-color 0.5s ease;
}

.d1 {
  top: 40px;
  left: 40px;
  width: 16px;
  height: 16px;
  animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}

.d2 {
  top: 80px;
  right: 80px;
  width: 24px;
  height: 24px;
  animation: bounce 1s infinite;
}

.d3 {
  bottom: 80px;
  left: 80px;
  width: 12px;
  height: 12px;
  animation: ping 1.6s cubic-bezier(0, 0, 0.2, 1) infinite;
}

.d4 {
  bottom: 40px;
  right: 40px;
  width: 20px;
  height: 20px;
  animation: pulse 2.4s cubic-bezier(0.4, 0, 0.6, 1) 0.4s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

@keyframes bounce {
  0%, 100% {
    transform: translateY(-25%);
    animation-timing-function: cubic-bezier(0.8, 0, 1, 1);
  }
  50% {
    transform: none;
    animation-timing-function: cubic-bezier(0, 0, 0.2, 1);
  }
}

@keyframes ping {
  0% { transform: scale(1); opacity: 1; }
  75%, 100% { transform: scale(2.2); opacity: 0; }
}

</style>
