<template>
  <el-dialog
    v-model="visible"
    title="标签管理"
    width="440px"
    destroy-on-close
    append-to-body
  >
    <!-- 新建标签 (OWNER/ADMIN) -->
    <div v-if="canManage" class="tag-create-row">
      <el-input
        v-model.trim="newTagName"
        placeholder="输入标签名称"
        maxlength="64"
        clearable
        @keyup.enter="handleCreate"
      />
      <el-button type="primary" :loading="creating" @click="handleCreate">
        新建标签
      </el-button>
    </div>

    <div v-loading="loading" class="tag-list">
      <EmptyState
        v-if="!loading && tags.length === 0"
        :icon="TagIcon"
        title="还没有标签"
        description="创建标签后即可为文档分类"
      />
      <div v-for="tag in tags" :key="tag.id" class="tag-row">
        <span class="tag-chip" :style="tagStyle(tag.name)">
          <span class="tag-chip-dot" :style="{ backgroundColor: tagStyle(tag.name).color }"></span>
          {{ tag.name }}
        </span>
        <div v-if="canManage" class="tag-row-actions">
          <el-button link size="small" @click="handleRename(tag)">重命名</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(tag)">删除</el-button>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Tag as TagIcon } from 'lucide-vue-next'
import { listTagsApi, createTagApi, renameTagApi, deleteTagApi } from '@/api/tag'
import { tagStyle } from '@/utils/tagColors'
import EmptyState from '@/components/EmptyState.vue'

const props = defineProps({
  spaceId: { type: [Number, String], required: true },
  myRole: { type: String, default: '' }
})

const emit = defineEmits(['changed'])

const visible = defineModel({ type: Boolean, default: false })

const loading = ref(false)
const tags = ref([])
const newTagName = ref('')
const creating = ref(false)

const canManage = computed(() => props.myRole === 'OWNER' || props.myRole === 'ADMIN')

watch(visible, (val) => {
  if (val) loadTags()
})

async function loadTags() {
  loading.value = true
  try {
    tags.value = await listTagsApi(props.spaceId)
  } catch (err) {
    tags.value = []
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  const name = newTagName.value.trim()
  if (!name) {
    ElMessage.warning('请输入标签名称')
    return
  }
  if (name.length > 64) {
    ElMessage.warning('标签名称不能超过 64 个字符')
    return
  }
  creating.value = true
  try {
    await createTagApi(props.spaceId, name)
    ElMessage.success('标签创建成功')
    newTagName.value = ''
    await loadTags()
    emit('changed')
  } catch (err) {
    // 拦截器处理
  } finally {
    creating.value = false
  }
}

function handleRename(tag) {
  ElMessageBox.prompt('请输入新标签名', '重命名标签', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: tag.name,
    inputValidator: (val) => {
      if (!val || !val.trim()) return '标签名不能为空'
      if (val.trim().length > 64) return '标签名不能超过 64 个字符'
      return true
    }
  }).then(async ({ value }) => {
    try {
      await renameTagApi(props.spaceId, tag.id, value.trim())
      ElMessage.success('标签重命名成功')
      await loadTags()
      emit('changed')
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}

function handleDelete(tag) {
  ElMessageBox.confirm(
    `删除标签 "${tag.name}" 后，已打该标签的文档不受影响。确定删除吗？`,
    '删除标签',
    {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(async () => {
    try {
      await deleteTagApi(props.spaceId, tag.id)
      ElMessage.success('标签已删除')
      await loadTags()
      emit('changed')
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}
</script>

<style scoped>
.tag-create-row {
  display: flex;
  gap: 8px;
  margin-bottom: 1rem;
}

.tag-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 320px;
  overflow-y: auto;
  min-height: 100px;
}

.tag-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 8px;
  border-radius: 6px;
}

.tag-row:hover {
  background-color: var(--app-hover-soft);
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.8rem;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid transparent;
}

.tag-chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.tag-row-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}
</style>
