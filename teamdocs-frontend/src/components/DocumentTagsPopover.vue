<template>
  <el-dialog
    v-model="visible"
    title="文档标签"
    width="420px"
    destroy-on-close
    append-to-body
  >
    <p class="doc-name-line" :title="documentName">{{ documentName }}</p>

    <div class="tags-select-area">
      <EmptyState
        v-if="allTags.length === 0"
        :icon="TagIcon"
        title="空间还没有标签"
        description="先在「标签管理」里创建，再回来为文档打标"
      />
      <template v-else>
        <p class="tags-hint">点击标签即可为文档添加或移除</p>
        <div class="tags-wrap">
          <span
            v-for="tag in allTags"
            :key="tag.id"
            :class="['doc-tag-item', { checked: isAttached(tag), 'is-busy': busyTagId === tag.id }]"
            :style="isAttached(tag) ? tagStyle(tag.name) : {}"
            @click="handleToggle(tag)"
          >
            <el-icon v-if="isAttached(tag)" class="tag-check-icon"><Check /></el-icon>
            {{ tag.name }}
          </span>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { Check, Tag as TagIcon } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import { addTagToDocumentApi, removeTagFromDocumentApi } from '@/api/tag'
import { tagStyle } from '@/utils/tagColors'

// 标签全集与已打标集合都由父级传入 (来自工作台的 docTagsMap)，
// 弹窗自身不再发任何查询请求——曾经的逐标签反查 N+1 已收敛到父级一处。
const props = defineProps({
  spaceId: { type: [Number, String], required: true },
  documentId: { type: [Number, String], default: null },
  documentName: { type: String, default: '' },
  allTags: { type: Array, default: () => [] },
  attachedTagNames: { type: Array, default: () => [] }
})

const emit = defineEmits(['changed'])

const visible = defineModel({ type: Boolean, default: false })

const busyTagId = ref(null)

function isAttached(tag) {
  return props.attachedTagNames.includes(tag.name)
}

async function handleToggle(tag) {
  if (busyTagId.value !== null) return
  const added = !isAttached(tag)
  busyTagId.value = tag.id
  try {
    if (added) {
      await addTagToDocumentApi(props.spaceId, props.documentId, tag.id)
    } else {
      await removeTagFromDocumentApi(props.spaceId, props.documentId, tag.id)
    }
    emit('changed', { docId: props.documentId, tag, added })
  } catch (err) {
    // 拦截器提示；失败不 emit，勾选态保持不变
  } finally {
    busyTagId.value = null
  }
}
</script>

<style scoped>
.doc-name-line {
  font-size: 0.825rem;
  color: var(--app-text-muted);
  margin: 0 0 0.75rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tags-select-area {
  min-height: 100px;
}

.tags-hint {
  font-size: 0.75rem;
  color: var(--app-text-faint);
  margin: 0 0 0.6rem;
}

.tags-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.doc-tag-item {
  user-select: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 0.8rem;
  font-weight: 500;
  padding: 4px 12px;
  border-radius: 999px;
  border: 1px solid var(--app-border);
  color: var(--app-text-muted);
  background: var(--app-panel);
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-standard);
}

.doc-tag-item:hover {
  border-color: var(--app-text-faint);
  color: var(--app-text-2);
  transform: translateY(-1px);
}

.doc-tag-item.checked {
  border-color: currentColor;
}

.doc-tag-item.is-busy {
  opacity: 0.5;
  pointer-events: none;
}

.tag-check-icon {
  font-size: 12px;
}
</style>
