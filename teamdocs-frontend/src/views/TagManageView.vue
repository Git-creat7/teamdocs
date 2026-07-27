<template>
  <div class="tag-page">
    <div class="tag-container">
      <!-- 页头 -->
      <div class="page-head anim-item" style="--delay: 0">
        <div class="page-head-main">
          <h1 class="page-title">标签管理</h1>
          <p class="page-sub">管理你所有空间的标签</p>
          <p class="page-stats">
            <template v-if="!loading">
              {{ stats.tagCount }} 个标签 · 覆盖 {{ stats.docCover }} 篇文档
              <template v-if="stats.unusedCount > 0"> · {{ stats.unusedCount }} 个未使用</template>
            </template>
            <template v-else>加载中…</template>
          </p>
        </div>
        <el-button
          type="primary"
          class="create-btn"
          :disabled="manageableSpaces.length === 0"
          @click="openCreateDialog"
        >
          <el-icon><Plus /></el-icon>
          新建标签
        </el-button>
      </div>

      <!-- 工具条：搜索 + 空间筛选 -->
      <div class="toolbar anim-item" style="--delay: 1">
        <el-input
          v-model.trim="keyword"
          class="search-input"
          placeholder="搜索标签…"
          clearable
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-select v-model="filterSpaceId" class="space-filter" placeholder="全部空间">
          <el-option label="全部空间" :value="0" />
          <el-option
            v-for="space in spaces"
            :key="space.id"
            :label="space.name"
            :value="space.id"
          />
        </el-select>
      </div>

      <!-- 主体 -->
      <div v-loading="loading" class="tag-body anim-item" style="--delay: 2">
        <EmptyState
          v-if="!loading && spaces.length === 0"
          :icon="FolderOpen"
          title="还没有空间"
          description="先创建一个空间，再为它添加标签"
        />

        <EmptyState
          v-else-if="!loading && filteredGroups.length === 0"
          :icon="TagIcon"
          title="没有匹配的标签"
          description="试试换个关键词，或切换空间筛选"
        />

        <div
          v-for="group in filteredGroups"
          :key="group.space.id"
          class="space-group"
        >
          <!-- 空间头 -->
          <div class="space-group-head">
            <div class="space-group-left">
              <el-icon class="space-folder-icon"><Folder /></el-icon>
              <span class="space-group-name">{{ group.space.name }}</span>
            </div>
            <span :class="['role-badge', roleBadgeClass(group.space.myRole)]">
              {{ roleLabel(group.space.myRole) }}
            </span>
          </div>

          <!-- 标签行 -->
          <div v-if="group.tags.length === 0" class="tag-empty-row">
            该空间还没有标签
            <el-button
              v-if="canManage(group.space.myRole)"
              link
              type="primary"
              size="small"
              @click="openCreateDialog(group.space.id)"
            >
              新建
            </el-button>
          </div>

          <div
            v-for="tag in group.tags"
            :key="tag.id"
            class="tag-row"
          >
            <div class="tag-row-left">
              <span
                class="tag-dot"
                :style="{ backgroundColor: tagDotColor(tag) }"
              ></span>
              <span class="tag-name" :title="tag.name">{{ tag.name }}</span>
            </div>

            <div class="tag-row-mid">
              <span class="tag-count">
                <template v-if="tag.docCount == null">…</template>
                <template v-else>{{ tag.docCount }} 篇文档</template>
              </span>
              <span v-if="tag.docCount === 0" class="unused-badge">未使用</span>
            </div>

            <div class="tag-row-actions">
              <template v-if="canManage(group.space.myRole)">
                <el-button link size="small" @click="handleRename(group.space, tag)">
                  重命名
                </el-button>
                <el-button
                  link
                  size="small"
                  type="danger"
                  @click="handleDelete(group.space, tag)"
                >
                  删除
                </el-button>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建标签 -->
    <el-dialog
      v-model="createVisible"
      title="新建标签"
      width="420px"
      destroy-on-close
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
        @keyup.enter="handleCreate"
      >
        <el-form-item label="所属空间" prop="spaceId">
          <el-select
            v-model="createForm.spaceId"
            placeholder="选择空间"
            style="width: 100%"
          >
            <el-option
              v-for="space in manageableSpaces"
              :key="space.id"
              :label="space.name"
              :value="space.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="标签名称" prop="name">
          <el-input
            v-model.trim="createForm.name"
            placeholder="请输入标签名称"
            maxlength="64"
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="handleCreate">
            创建
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Folder, FolderOpen, Tag as TagIcon } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { useSpacesStore } from '@/stores'
import {
  listTagsApi,
  createTagApi,
  renameTagApi,
  deleteTagApi,
  listDocumentsByTagApi
} from '@/api/tag'
import { tagPalette } from '@/utils/tagColors'
import EmptyState from '@/components/EmptyState.vue'

const spacesStore = useSpacesStore()
const { spaces } = storeToRefs(spacesStore)

const loading = ref(true)
const keyword = ref('')
const filterSpaceId = ref(0)

/** @type {import('vue').Ref<Array<{ space: any, tags: Array<any> }>>} */
const groups = ref([])

const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref(null)
const createForm = reactive({
  spaceId: null,
  name: ''
})

const createRules = {
  spaceId: [{ required: true, message: '请选择空间', trigger: 'change' }],
  name: [
    { required: true, message: '请输入标签名称', trigger: 'blur' },
    { min: 1, max: 64, message: '标签名称最长 64 个字符', trigger: 'blur' }
  ]
}

const manageableSpaces = computed(() =>
  spaces.value.filter((s) => canManage(s.myRole))
)

const filteredGroups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const sid = filterSpaceId.value

  return groups.value
    .filter((g) => !sid || g.space.id === sid)
    .map((g) => {
      if (!kw) return g
      return {
        ...g,
        tags: g.tags.filter((t) => String(t.name || '').toLowerCase().includes(kw))
      }
    })
    .filter((g) => {
      // 有关键词时：只保留命中标签的空间；无关键词：全部空间都展示（含空）
      if (kw) return g.tags.length > 0
      if (sid) return true
      return true
    })
})

const stats = computed(() => {
  let tagCount = 0
  let docCover = 0
  let unusedCount = 0
  for (const g of groups.value) {
    for (const t of g.tags) {
      tagCount += 1
      if (typeof t.docCount === 'number') {
        docCover += t.docCount
        if (t.docCount === 0) unusedCount += 1
      }
    }
  }
  return { tagCount, docCover, unusedCount }
})

onMounted(async () => {
  if (spaces.value.length === 0) {
    await spacesStore.refresh()
  }
  await loadAll()
})

function canManage(role) {
  return role === 'OWNER' || role === 'ADMIN'
}

function roleLabel(role) {
  if (role === 'OWNER') return '所有者'
  if (role === 'ADMIN') return '管理员'
  if (role === 'MEMBER') return '成员'
  return role || '成员'
}

function roleBadgeClass(role) {
  if (role === 'OWNER') return 'is-owner'
  if (role === 'ADMIN') return 'is-admin'
  return 'is-member'
}

/** 彩色圆点：按标签名哈希取固定色（无后端 color 字段） */
function tagDotColor(tag) {
  return tagPalette(tag?.name || tag?.id).text
}

async function loadAll() {
  loading.value = true
  try {
    const list = spaces.value
    if (list.length === 0) {
      groups.value = []
      return
    }

    const settled = await Promise.all(
      list.map(async (space) => {
        try {
          const tags = await listTagsApi(space.id)
          return {
            space,
            tags: tags.map((t) => ({
              ...t,
              spaceId: space.id,
              docCount: null
            }))
          }
        } catch (err) {
          return { space, tags: [] }
        }
      })
    )
    groups.value = settled

    // 后台并发拉每个标签的文档数（size=1 只取 total），不阻塞首屏
    fillDocCounts()
  } finally {
    loading.value = false
  }
}

async function fillDocCounts() {
  const tasks = []
  for (const g of groups.value) {
    for (const tag of g.tags) {
      tasks.push({ spaceId: g.space.id, tag })
    }
  }
  if (tasks.length === 0) return

  const CONCURRENCY = 6
  let cursor = 0

  async function worker() {
    while (cursor < tasks.length) {
      const i = cursor++
      const { spaceId, tag } = tasks[i]
      try {
        const page = await listDocumentsByTagApi(spaceId, tag.id, 1, 1)
        tag.docCount = Number(page.total) || 0
      } catch (err) {
        tag.docCount = 0
      }
    }
  }

  await Promise.all(
    Array.from({ length: Math.min(CONCURRENCY, tasks.length) }, () => worker())
  )
}

function openCreateDialog(presetSpaceId) {
  if (manageableSpaces.value.length === 0) {
    ElMessage.warning('你没有可管理标签的空间（需要所有者或管理员）')
    return
  }
  const preferred =
    presetSpaceId && manageableSpaces.value.some((s) => s.id === presetSpaceId)
      ? presetSpaceId
      : (filterSpaceId.value &&
          manageableSpaces.value.some((s) => s.id === filterSpaceId.value)
          ? filterSpaceId.value
          : manageableSpaces.value[0].id)

  createForm.spaceId = preferred
  createForm.name = ''
  createVisible.value = true
}

async function handleCreate() {
  if (!createFormRef.value) return
  try {
    await createFormRef.value.validate()
  } catch (err) {
    return
  }

  creating.value = true
  try {
    await createTagApi(createForm.spaceId, createForm.name.trim())
    ElMessage.success('标签创建成功')
    createVisible.value = false
    await loadAll()
  } catch (err) {
    // 拦截器处理
  } finally {
    creating.value = false
  }
}

function handleRename(space, tag) {
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
      await renameTagApi(space.id, tag.id, value.trim())
      ElMessage.success('标签重命名成功')
      tag.name = value.trim()
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}

function handleDelete(space, tag) {
  ElMessageBox.confirm(
    `删除标签「${tag.name}」后，已打该标签的文档不受影响。确定删除吗？`,
    '删除标签',
    {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(async () => {
    try {
      await deleteTagApi(space.id, tag.id)
      ElMessage.success('标签已删除')
      const group = groups.value.find((g) => g.space.id === space.id)
      if (group) {
        group.tags = group.tags.filter((t) => t.id !== tag.id)
      }
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}
</script>

<style scoped>
.tag-page {
  flex: 1;
  overflow-y: auto;
  padding: 2rem 2.5rem 3rem;
}

.tag-container {
  max-width: 920px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 1.25rem;
}

.page-title {
  font-size: 1.4rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0 0 4px;
}

.page-sub {
  margin: 0;
  font-size: 0.9rem;
  color: var(--app-text-muted);
}

.page-stats {
  margin: 6px 0 0;
  font-size: 0.8rem;
  color: var(--app-text-faint);
}

.create-btn {
  flex-shrink: 0;
  border-radius: 8px;
  font-weight: 500;
}

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 1.25rem;
}

.search-input {
  flex: 1;
  max-width: 320px;
}

.space-filter {
  width: 160px;
  flex-shrink: 0;
}

.tag-body {
  min-height: 200px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.space-group {
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
}

.space-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: var(--app-panel-soft);
  border-bottom: 1px solid var(--app-border-soft);
}

.space-group-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.space-folder-icon {
  color: var(--app-text-muted);
  flex-shrink: 0;
}

.space-group-name {
  font-size: 0.925rem;
  font-weight: 600;
  color: var(--app-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.role-badge {
  flex-shrink: 0;
  font-size: 0.72rem;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 999px;
  line-height: 1.5;
}

.role-badge.is-owner {
  background: #f1f5f9;
  color: #475569;
}

.role-badge.is-admin {
  background: #eff6ff;
  color: #2563eb;
}

.role-badge.is-member {
  background: #f8fafc;
  color: #64748b;
  border: 1px solid var(--app-border);
}

.tag-empty-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  font-size: 0.85rem;
  color: var(--app-text-faint);
}

.tag-row {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 11px 16px;
  border-top: 1px solid var(--app-border-soft);
  transition: background-color var(--dur-fast) var(--ease-standard);
}

.tag-row:first-of-type {
  border-top: none;
}

.tag-row:hover {
  background: var(--app-hover-soft);
}

.tag-row-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.tag-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.tag-name {
  font-size: 0.9rem;
  color: var(--app-text-2);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-row-mid {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.tag-count {
  font-size: 0.825rem;
  color: var(--app-text-muted);
  white-space: nowrap;
}

.unused-badge {
  font-size: 0.7rem;
  font-weight: 500;
  color: #94a3b8;
  background: #f1f5f9;
  padding: 1px 7px;
  border-radius: 4px;
  white-space: nowrap;
}

.tag-row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  min-width: 110px;
  opacity: 0;
  transition: opacity var(--dur-fast) var(--ease-standard);
}

.tag-row:hover .tag-row-actions {
  opacity: 1;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 640px) {
  .tag-page {
    padding: 1.25rem 1rem 2rem;
  }

  .page-head {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar {
    flex-direction: column;
  }

  .search-input {
    max-width: none;
  }

  .space-filter {
    width: 100%;
  }

  .tag-row {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .tag-row-actions {
    opacity: 1;
    min-width: 0;
    justify-content: flex-start;
  }
}
</style>
