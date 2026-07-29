<template>
  <div class="empty-root">
    <div class="empty-visual">
      <div class="empty-ring"></div>
      <div class="empty-icon-box">
        <component :is="icon" :size="26" :stroke-width="1.8" />
      </div>
    </div>
    <p class="empty-title">{{ title }}</p>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<script setup>
// 统一空状态：Lucide 图标 + 呼吸光环，替代 el-empty 的默认灰盒插画
defineProps({
  icon: { type: [Object, Function], required: true },
  title: { type: String, required: true },
  description: { type: String, default: '' }
})
</script>

<style scoped>
.empty-root {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 2.4rem 1rem;
}

.empty-visual {
  position: relative;
  width: 84px;
  height: 84px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 1.1rem;
}

.empty-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.14) 0%, transparent 68%);
  animation: ring-breathe 3.2s ease-in-out infinite;
}

@keyframes ring-breathe {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.18); opacity: 0.6; }
}

.empty-icon-box {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: #eef2ff;
  color: #6366f1;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 0 0 1px rgba(99, 102, 241, 0.12);
}

.empty-title {
  font-size: 0.92rem;
  font-weight: 600;
  color: var(--app-text-2);
  margin: 0 0 0.25rem;
}

.empty-desc {
  font-size: 0.8rem;
  color: var(--app-text-faint);
  margin: 0;
  max-width: 26em;
  text-align: center;
}

.empty-action {
  margin-top: 1rem;
}
</style>
