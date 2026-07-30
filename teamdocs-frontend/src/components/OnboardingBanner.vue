<template>
  <!-- 进度横幅：有空间但三步未走完时显示 -->
  <div v-if="visible" class="onboarding-banner">
    <button type="button" class="banner-close" aria-label="关闭" @click="dismiss">
      <el-icon><Close /></el-icon>
    </button>

    <div class="banner-text">
      <h3 class="banner-title">欢迎使用 TeamDocs</h3>
      <p class="banner-sub">三步开始团队协作</p>
    </div>

    <div class="banner-steps">
      <template v-for="(step, i) in steps" :key="step.name">
        <div class="banner-step" :class="{ done: step.done }">
          <span class="step-check">
            <el-icon v-if="step.done" :size="12"><Check /></el-icon>
          </span>
          <span class="step-label">{{ step.name }}</span>
        </div>
        <span v-if="i < steps.length - 1" class="step-line"></span>
      </template>
    </div>
  </div>

  <!-- 完成态：三步全完成后给小条收尾，关闭后永久收起 -->
  <div v-else-if="justCompleted" class="onboarding-done">
    <span>一切就绪，开始协作吧</span>
    <button type="button" class="done-close" @click="dismissDone">关闭</button>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Check, Close } from '@element-plus/icons-vue'

// 进度数据全部来自 /space/list 聚合字段 (docCount/memberCount)，无需额外接口
const props = defineProps({
  spaces: { type: Array, default: () => [] }
})

const DISMISS_KEY = 'onboarding_dismissed'
const DONE_KEY = 'onboarding_done_dismissed'

const dismissed = ref(localStorage.getItem(DISMISS_KEY) === '1')
const doneDismissed = ref(localStorage.getItem(DONE_KEY) === '1')

const steps = computed(() => {
  const list = props.spaces
  return [
    { name: '创建空间', done: list.length > 0 },
    { name: '上传文档', done: list.some((s) => Number(s.docCount) > 0) },
    { name: '邀请成员', done: list.some((s) => Number(s.memberCount) > 1) }
  ]
})

const allDone = computed(() => steps.value.every((s) => s.done))

// 一个空间都没有时让位给空态大引导卡 (onboard-card)，不重复引导
const visible = computed(
  () => props.spaces.length > 0 && !allDone.value && !dismissed.value
)
const justCompleted = computed(() => allDone.value && !doneDismissed.value)

function dismiss() {
  dismissed.value = true
  localStorage.setItem(DISMISS_KEY, '1')
}

function dismissDone() {
  doneDismissed.value = true
  localStorage.setItem(DONE_KEY, '1')
}
</script>

<style scoped>
.onboarding-banner {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 28px;
  border-radius: 14px;
  background: linear-gradient(120deg, var(--app-accent-weak) 0%, var(--app-panel) 90%);
  border: 1px solid var(--app-border);
}

.banner-close {
  position: absolute;
  top: 10px;
  right: 12px;
  border: none;
  background: none;
  padding: 4px;
  cursor: pointer;
  color: var(--app-text-faint);
  border-radius: 6px;
  display: flex;
  transition: color var(--dur-fast) var(--ease-standard),
    background-color var(--dur-fast) var(--ease-standard);
}

.banner-close:hover {
  color: var(--app-text);
  background: var(--app-hover);
}

.banner-text {
  min-width: 0;
}

.banner-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 700;
  color: var(--app-text);
}

.banner-sub {
  margin: 4px 0 0;
  font-size: 0.8rem;
  color: var(--app-text-muted);
}

.banner-steps {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.banner-step {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 0.85rem;
  color: var(--app-text-muted);
}

.step-check {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid var(--app-text-faint);
  color: var(--app-text-faint);
  flex-shrink: 0;
}

.banner-step.done {
  color: var(--app-text);
  font-weight: 500;
}

.banner-step.done .step-check {
  background: var(--app-accent);
  border-color: var(--app-accent);
  color: #ffffff;
}

.step-line {
  width: 42px;
  border-top: 1.5px dashed var(--app-border);
}

/* 完成态小条 */
.onboarding-done {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 0.82rem;
  color: var(--app-text-muted);
  padding: 4px 0;
}

.done-close {
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  font-size: 0.82rem;
  color: var(--app-text-faint);
  text-decoration: underline;
}

.done-close:hover {
  color: var(--app-text);
}

@media (max-width: 768px) {
  .onboarding-banner {
    flex-direction: column;
    align-items: flex-start;
    gap: 14px;
    padding: 16px 18px;
  }

  .banner-steps {
    width: 100%;
    justify-content: space-between;
    gap: 8px;
  }

  .step-line {
    flex: 1;
    width: auto;
  }
}
</style>
