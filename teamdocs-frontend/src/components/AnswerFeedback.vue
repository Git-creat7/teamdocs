<template>
  <div class="answer-feedback">
    <el-button text size="small" :disabled="busy" :aria-pressed="value?.rating === 'UP'" class="feedback-btn" @click="submit(value?.rating === 'UP' ? 'NONE' : 'UP')">
      <ThumbsUp :size="13" class="feedback-icon" />
      <span>{{ value?.rating === 'UP' ? '已赞' : '点赞' }}</span>
    </el-button>
    <el-button text size="small" :disabled="busy" :aria-pressed="value?.rating === 'DOWN'" class="feedback-btn" @click="negative = !negative">
      <ThumbsDown :size="13" class="feedback-icon" />
      <span>{{ value?.rating === 'DOWN' ? '已踩' : '点踩' }}</span>
    </el-button>
    <div v-if="negative" class="feedback-reasons">
      <el-button v-for="item in reasons" :key="item[0]" size="small" :disabled="busy" @click="submit('DOWN', item[0])">{{ item[1] }}</el-button>
      <el-button v-if="value" size="small" text :disabled="busy" @click="submit('NONE')">撤回反馈</el-button>
    </div>
    <small v-if="error" role="alert">{{ error }}</small>
  </div>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { ThumbsUp, ThumbsDown } from 'lucide-vue-next'
import { getFeedback, saveFeedback } from '@/api/documentTools'
const props = defineProps({ spaceId: [String, Number], runId: [String, Number] })
const value = ref(null), busy = ref(false), negative = ref(false), error = ref('')
const reasons = [['WRONG_CITATION', '引用不对'], ['INCOMPLETE', '回答不完整'], ['NOT_FOUND', '没找到文档'], ['OTHER', '其他']]
let controller
watch(() => [props.spaceId, props.runId], async () => {
  controller?.abort(); const request = controller = new AbortController()
  value.value = null; error.value = ''; busy.value = true; negative.value = false
  try { const result = await getFeedback(props.spaceId, props.runId, request.signal); if (!request.signal.aborted) value.value = result }
  catch (e) { if (!request.signal.aborted) error.value = e.message }
  finally { if (!request.signal.aborted) busy.value = false }
}, { immediate: true })
async function submit(rating, reason = null) {
  if (busy.value) return
  const request = controller; busy.value = true; error.value = ''
  try { const result = await saveFeedback(props.spaceId, props.runId, { rating, reason }, request.signal); if (!request.signal.aborted) { value.value = result; negative.value = false } }
  catch (e) { if (!request.signal.aborted) error.value = e.message || '反馈保存失败' }
  finally { if (!request.signal.aborted) busy.value = false }
}
onBeforeUnmount(() => controller?.abort())
</script>
<style scoped>
.answer-feedback {
  margin-top: 12px;
}

.feedback-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.feedback-icon {
  flex-shrink: 0;
}

.feedback-reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

small {
  display: block;
  color: var(--el-color-danger);
}

</style>
