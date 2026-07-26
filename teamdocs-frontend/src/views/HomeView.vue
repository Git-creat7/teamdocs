<template>
  <div class="home-page">
    <div class="home-container">
      <!-- 问候区：白色简洁版 -->
      <div class="greeting-block anim-item" style="--delay: 0">
        <div class="greeting-text">
          <h1 class="greeting-title">{{ greeting }}，{{ displayName }}</h1>
          <p class="greeting-sub">{{ todayText }}</p>
        </div>
        <div class="greeting-actions">
          <el-button type="primary" class="greet-btn" @click="editVisible = true; editingSpace = null; resetForm()">
            <el-icon><Plus /></el-icon>
            新建空间
          </el-button>
          <el-button class="greet-btn" @click="router.push('/recent')">
            继续上次浏览
          </el-button>
        </div>
      </div>

      <!-- 快捷动作入口：Notion 式粉彩底卡片 -->
      <div class="stats-row anim-item" style="--delay: 1">
        <div class="stat-card is-clickable tint-sky" @click="goSpacePanel('members')">
          <div class="stat-icon-box">
            <el-icon :size="22"><User /></el-icon>
          </div>
          <div class="stat-body">
            <span class="stat-value-text">添加成员</span>
            <span class="stat-label">邀请伙伴进空间协作</span>
          </div>
          <el-icon class="stat-arrow"><ChevronRight /></el-icon>
        </div>

        <div class="stat-card is-clickable tint-lavender" @click="goSpacePanel('tags')">
          <div class="stat-icon-box">
            <el-icon :size="22"><Tag /></el-icon>
          </div>
          <div class="stat-body">
            <span class="stat-value-text">标签管理</span>
            <span class="stat-label">为文档建立分类体系</span>
          </div>
          <el-icon class="stat-arrow"><ChevronRight /></el-icon>
        </div>

        <div class="stat-card is-clickable tint-peach" @click="goTrash">
          <div class="stat-icon-box">
            <el-icon :size="22"><Trash2 /></el-icon>
          </div>
          <div class="stat-body">
            <span class="stat-value-text">回收站</span>
            <span class="stat-label">已删文档可恢复</span>
          </div>
          <el-icon class="stat-arrow"><ChevronRight /></el-icon>
        </div>

        <div class="stat-card is-clickable tint-mint" @click="router.push('/recent')">
          <div class="stat-icon-box">
            <el-icon :size="22"><Clock /></el-icon>
          </div>
          <div class="stat-body">
            <span class="stat-value-text">最近浏览</span>
            <span class="stat-label">继续上次看的文档</span>
          </div>
          <el-icon class="stat-arrow"><ChevronRight /></el-icon>
        </div>
      </div>

      <!-- 最近浏览 -->
      <section class="home-section anim-item" style="--delay: 2">
        <div class="section-head">
          <h2 class="section-title">最近浏览</h2>
          <el-button
            v-if="recentDocs.length > 0"
            link
            type="primary"
            size="small"
            @click="router.push('/recent')"
          >
            查看全部
          </el-button>
        </div>

        <div v-if="loadingRecent" class="recent-grid">
          <div v-for="i in 4" :key="i" class="recent-card is-skeleton">
            <el-skeleton :rows="2" animated />
          </div>
        </div>

        <EmptyState
          v-else-if="recentDocs.length === 0"
          :icon="History"
          title="最近还没有浏览过文档"
          description="打开任意文档后，会在这里留下入口"
        />

        <div v-else class="recent-grid">
          <div
            v-for="doc in recentDocs.slice(0, 8)"
            :key="doc.documentId"
            class="recent-card"
            @click="openRecentDoc(doc)"
          >
            <div class="recent-card-top">
              <FileIcon :ext="getFileExt(doc.name, doc.fileType)" :size="26" />
              <span class="recent-card-name" :title="doc.name">{{ doc.name }}</span>
            </div>
            <div class="recent-card-meta">
              <span class="recent-space-name">{{ doc.spaceName }}</span>
              <span class="recent-time">{{ formatDateTime(doc.lastViewedAt) }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 我的空间 -->
      <section class="home-section anim-item" style="--delay: 3">
        <div class="section-head">
          <h2 class="section-title">我的空间</h2>
        </div>

        <!-- SWR：骨架仅首载 (无旧数据) 时出现，后台刷新不打断已渲染内容 -->
        <div v-if="spacesLoading && spaces.length === 0" class="space-grid">
          <div v-for="i in 3" :key="i" class="space-card is-skeleton">
            <el-skeleton :rows="3" animated />
          </div>
        </div>

        <div v-else class="space-grid">
          <div
            v-for="space in spaces"
            :key="space.id"
            class="space-card"
            @click="router.push(`/spaces/${space.id}`)"
          >
            <div class="space-card-head">
              <div
                class="space-icon-box"
                :style="{ backgroundColor: spaceIconPalette(space.id).bg, color: spaceIconPalette(space.id).text }"
              >
                <el-icon :size="18"><FolderOpen /></el-icon>
              </div>
              <h3 class="space-card-name" :title="space.name">{{ space.name }}</h3>
              <span
                v-if="space.myRole"
                :class="['role-chip', `role-${space.myRole.toLowerCase()}`]"
              >
                {{ roleText(space.myRole) }}
              </span>
              <el-dropdown
                trigger="click"
                @command="(cmd) => handleSpaceCommand(cmd, space)"
              >
                <span class="space-more-btn" @click.stop>
                  <el-icon><MoreHorizontal /></el-icon>
                </span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">
                      <el-icon><Pencil /></el-icon>编辑空间
                    </el-dropdown-item>
                    <el-dropdown-item command="delete" divided class="danger-item">
                      <el-icon><Trash2 /></el-icon>删除空间
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <p class="space-card-desc">{{ space.description || '暂无描述' }}</p>
            <div v-if="(spaceTagsMap[space.id] || []).length" class="space-card-tags">
              <span
                v-for="tag in spaceTagsMap[space.id].slice(0, 4)"
                :key="tag.id"
                class="space-tag-chip"
                :style="tagStyle(tag.name)"
              >
                {{ tag.name }}
              </span>
              <span v-if="spaceTagsMap[space.id].length > 4" class="space-tag-more">
                +{{ spaceTagsMap[space.id].length - 4 }}
              </span>
            </div>
            <div class="space-card-foot">
              <span class="space-card-date">创建于 {{ formatDate(space.createdAt) }}</span>
              <span class="space-card-members">
                <span v-if="space.docCount" class="foot-stat">
                  <el-icon :size="13"><FileText /></el-icon>
                  {{ space.docCount }}
                </span>
                <span v-if="space.memberCount" class="foot-stat">
                  <el-icon :size="13"><User /></el-icon>
                  {{ space.memberCount }}
                </span>
              </span>
            </div>
          </div>

          <!-- 新建空间虚线卡片 -->
          <div class="space-card-dashed" @click="editVisible = true; editingSpace = null; resetForm()">
            <el-icon :size="20"><Plus /></el-icon>
            <span>创建新空间</span>
          </div>
        </div>
      </section>
    </div>

    <!-- 选择空间 (成员/标签直达入口用) -->
    <el-dialog
      v-model="spacePickVisible"
      :title="panelChoice === 'members' ? '选择要管理成员的空间' : '选择要管理标签的空间'"
      width="400px"
      destroy-on-close
    >
      <div class="space-pick-list">
        <div
          v-for="space in spaces"
          :key="space.id"
          class="space-pick-item"
          @click="pickSpaceForPanel(space)"
        >
          <div
            class="space-icon-box"
            :style="{ backgroundColor: spaceIconPalette(space.id).bg, color: spaceIconPalette(space.id).text }"
          >
            <el-icon :size="16"><FolderOpen /></el-icon>
          </div>
          <span class="space-pick-name">{{ space.name }}</span>
          <el-icon class="space-pick-arrow"><ChevronRight /></el-icon>
        </div>
      </div>
    </el-dialog>

    <!-- 编辑/新建空间 -->
    <el-dialog
      v-model="editVisible"
      :title="editingSpace ? '编辑空间' : '新建空间'"
      width="420px"
      destroy-on-close
    >
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-position="top"
      >
        <el-form-item label="空间名称" prop="name">
          <el-input v-model.trim="editForm.name" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="空间描述" prop="description">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="3"
            maxlength="255"
            placeholder="可选"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            {{ editingSpace ? '保存' : '创建' }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FolderOpen, MoreHorizontal, Pencil, Trash2, Plus, Clock, ChevronRight, User, Tag, History, FileText } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import FileIcon from '@/components/FileIcon.vue'
import { getRecentDocumentsApi } from '@/api/user'
import { createSpaceApi, updateSpaceApi, deleteSpaceApi } from '@/api/space'
import { listTagsApi } from '@/api/tag'
import { storeToRefs } from 'pinia'
import { useUserStore, useSpacesStore } from '@/stores'
import { formatDateTime, getFileExt, getFileTypeColor, buildRecentDocRoute } from '@/utils/format'
import { tagStyle } from '@/utils/tagColors'
import { spaceIconPalette } from '@/utils/spaceColors'

const router = useRouter()
const spacesStore = useSpacesStore()
const { spaces, loading: spacesLoading } = storeToRefs(spacesStore)
const refreshSpaces = spacesStore.refresh
const { userInfo } = storeToRefs(useUserStore())

const recentDocs = ref([])
const loadingRecent = ref(true)

// 角色/成员数/文档数已由 /space/list 聚合随行返回 (space.myRole/memberCount/docCount)，
// 这里只补 chips 用的标签列表——与角色数据彻底解耦
const spaceTagsMap = ref({})

function roleText(role) {
  return { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }[role] || ''
}

async function loadSpaceTagsMap() {
  const pairs = await Promise.all(
    spaces.value.map(async (s) => {
      try {
        return [s.id, await listTagsApi(s.id)]
      } catch (err) {
        return [s.id, []]
      }
    })
  )
  spaceTagsMap.value = Object.fromEntries(pairs)
}

watch(spaces, (list) => {
  if (list.length > 0) loadSpaceTagsMap()
}, { immediate: true })

const displayName = computed(() =>
  userInfo.value?.nickname || userInfo.value?.username || '朋友'
)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const todayText = computed(() => {
  const d = new Date()
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return `${d.getMonth() + 1}月${d.getDate()}日 ${weekdays[d.getDay()]}`
})

function goTrash() {
  const sid = spaces.value[0]?.id
  if (!sid) {
    ElMessage.warning('还没有空间，先创建一个吧')
    return
  }
  router.push({ path: '/trash', query: { spaceId: sid } })
}

// 直达空间的成员/标签面板：只有一个空间直接进，多个时让用户选
async function goSpacePanel(panel) {
  if (spaces.value.length === 0) {
    ElMessage.warning('还没有空间，先创建一个吧')
    return
  }
  if (spaces.value.length === 1) {
    router.push({ path: `/spaces/${spaces.value[0].id}`, query: { panel, t: Date.now() } })
    return
  }
  panelChoice.value = panel
  spacePickVisible.value = true
}

const spacePickVisible = ref(false)
const panelChoice = ref('')

function pickSpaceForPanel(space) {
  spacePickVisible.value = false
  router.push({ path: `/spaces/${space.id}`, query: { panel: panelChoice.value, t: Date.now() } })
}


onMounted(() => {
  loadRecent()
  // SWR：立即用 store 里的旧数据渲染，同时后台刷新 (上传/加成员后的数字不再说谎)
  refreshSpaces()
})

async function loadRecent() {
  loadingRecent.value = true
  try {
    recentDocs.value = await getRecentDocumentsApi()
  } catch (err) {
    recentDocs.value = []
  } finally {
    loadingRecent.value = false
  }
}

function openRecentDoc(doc) {
  router.push(buildRecentDocRoute(doc, ElMessage.info))
}

// 空间编辑/删除
const editVisible = ref(false)
const submitting = ref(false)
const editingSpace = ref(null)
const editFormRef = ref(null)
const editForm = reactive({ name: '', description: '' })

const editRules = {
  name: [
    { required: true, message: '请输入空间名称', trigger: 'blur' },
    { min: 1, max: 64, message: '空间名称长度在 1 到 64 个字符', trigger: 'blur' }
  ],
  description: [{ max: 255, message: '空间描述最长 255 个字符', trigger: 'blur' }]
}

function resetForm() {
  editForm.name = ''
  editForm.description = ''
}

function handleSpaceCommand(cmd, space) {
  if (cmd === 'edit') {
    editingSpace.value = space
    editForm.name = space.name || ''
    editForm.description = space.description || ''
    editVisible.value = true
  } else if (cmd === 'delete') {
    ElMessageBox.confirm(
      `删除空间 "${space.name}" 后，成员将无法再访问其中的文档。确定删除吗？`,
      '删除空间',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    ).then(async () => {
      try {
        await deleteSpaceApi(space.id)
        ElMessage.success('空间已删除')
        await refreshSpaces()
      } catch (err) {
        // 仅 OWNER 可删，权限错误由拦截器提示
      }
    }).catch(() => {})
  }
}

async function handleSubmit() {
  if (!editFormRef.value) return
  try {
    await editFormRef.value.validate()
  } catch (err) {
    return
  }
  submitting.value = true
  try {
    const payload = {
      name: editForm.name.trim(),
      description: editForm.description ? editForm.description.trim() : ''
    }
    if (editingSpace.value) {
      await updateSpaceApi(editingSpace.value.id, payload)
      ElMessage.success('空间已更新')
    } else {
      await createSpaceApi(payload)
      ElMessage.success('空间创建成功')
    }
    editVisible.value = false
    await refreshSpaces()
  } catch (err) {
    // 拦截器处理
  } finally {
    submitting.value = false
  }
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return '-'
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped>
.home-page {
  flex: 1;
  overflow-y: auto;
  padding: 2rem 2.5rem 3rem;
}

.home-container {
  max-width: 1080px;
  margin: 0 auto;
}

/* ===== 问候区：白色简洁版 ===== */
.greeting-block {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 1.75rem;
}

.greeting-text {
  min-width: 0;
}

.greeting-title {
  font-size: 1.6rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0 0 0.35rem;
}

.greeting-sub {
  font-size: 0.9rem;
  color: var(--app-text-muted);
  margin: 0;
}

.greeting-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.greet-btn {
  border-radius: 8px;
  font-weight: 500;
}

@media (max-width: 900px) {
  .greeting-block {
    flex-direction: column;
    align-items: flex-start;
  }
}


/* 入场动效使用 App.vue 全局的 .anim-item */

/* 快捷动作：4 列一行，统一主色浅底 */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
  margin-bottom: 2.25rem;
}

@media (max-width: 1100px) {
  .stats-row { grid-template-columns: repeat(2, 1fr); }
}

.stat-card {
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 14px;
  padding: 1.1rem 1.2rem;
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
  transition: transform var(--dur-fast) var(--ease-standard),
              box-shadow var(--dur-fast) var(--ease-standard),
              border-color var(--dur-fast) var(--ease-standard);
}

.stat-card.is-clickable {
  cursor: pointer;
}

.stat-card.is-clickable:hover {
  box-shadow: 0 8px 20px -6px rgba(55, 53, 47, 0.14);
  transform: translateY(-2px);
}

/* Notion 粉彩底：卡面着色，图标盒改半透明白 */
.tint-sky { background: #dcecfa; border-color: transparent; }
.tint-lavender { background: #e6e0f5; border-color: transparent; }
.tint-peach { background: #ffe8d4; border-color: transparent; }
.tint-mint { background: #d9f3e1; border-color: transparent; }

.tint-sky .stat-icon-box { color: #0075de; }
.tint-lavender .stat-icon-box { color: #5645d4; }
.tint-peach .stat-icon-box { color: #dd5b00; }
.tint-mint .stat-icon-box { color: #1aae39; }

.tint-sky .stat-value-text,
.tint-lavender .stat-value-text,
.tint-peach .stat-value-text,
.tint-mint .stat-value-text { color: #37352f; }

.tint-sky .stat-label,
.tint-lavender .stat-label,
.tint-peach .stat-label,
.tint-mint .stat-label { color: #5d5b54; }

.stat-icon-box {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.72);
  color: var(--app-accent);
}

.stat-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.stat-value-text {
  font-size: 0.98rem;
  font-weight: 600;
  color: var(--app-text);
  line-height: 1.3;
}

.stat-label {
  font-size: 0.78rem;
  color: var(--app-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stat-arrow {
  color: var(--app-text-faint);
  flex-shrink: 0;
  transition: transform 0.18s ease, color 0.15s;
}

.stat-card.is-clickable:hover .stat-arrow {
  color: var(--app-accent);
  transform: translateX(3px);
}

/* 选择空间弹层 */
.space-pick-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 320px;
  overflow-y: auto;
}

.space-pick-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 0.15s;
}

.space-pick-item:hover {
  background-color: var(--app-hover);
}

.space-pick-name {
  flex: 1;
  min-width: 0;
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.space-pick-arrow {
  color: var(--app-text-faint);
  flex-shrink: 0;
}

.space-pick-item:hover .space-pick-arrow {
  color: var(--app-accent);
}

.greeting-title {
  font-size: 1.6rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0 0 0.35rem;
}

.greeting-sub {
  font-size: 0.9rem;
  color: var(--app-text-muted);
  margin: 0;
}

.home-section {
  margin-bottom: 2.25rem;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.9rem;
}

.section-title {
  font-size: 1.05rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0;
}

/* 最近浏览卡片 */
.recent-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.9rem;
}

@media (max-width: 1100px) {
  .recent-grid { grid-template-columns: repeat(2, 1fr); }
}

.recent-card {
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 10px;
  padding: 0.9rem 1rem;
  cursor: pointer;
  transition: transform var(--dur-fast) var(--ease-standard),
              box-shadow var(--dur-fast) var(--ease-standard),
              border-color var(--dur-fast) var(--ease-standard);
  min-width: 0;
}

.recent-card:hover {
  transform: translateY(-2px);
}

.recent-card:hover {
  border-color: var(--app-text-faint);
  box-shadow: 0 4px 14px -4px rgba(15, 23, 42, 0.08);
}

.recent-card.is-skeleton {
  cursor: default;
}

.recent-card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 0.6rem;
  min-width: 0;
}

.ext-badge {
  font-size: 0.62rem;
  font-weight: 700;
  color: #ffffff;
  padding: 2px 5px;
  border-radius: 4px;
  letter-spacing: 0.5px;
  min-width: 28px;
  text-align: center;
  flex-shrink: 0;
}

.recent-card-name {
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.recent-space-name {
  font-size: 0.72rem;
  color: var(--app-accent);
  background: var(--app-accent-weak);
  border-radius: 4px;
  padding: 1px 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-time {
  font-size: 0.72rem;
  color: var(--app-text-faint);
  flex-shrink: 0;
}

/* 空间卡片 */
.space-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

@media (max-width: 1100px) {
  .space-grid { grid-template-columns: repeat(2, 1fr); }
}

.space-card {
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  padding: 1.2rem 1.3rem;
  cursor: pointer;
  transition: transform var(--dur-fast) var(--ease-standard),
              box-shadow var(--dur-fast) var(--ease-standard),
              border-color var(--dur-fast) var(--ease-standard);
  display: flex;
  flex-direction: column;
  min-height: 140px;
  box-sizing: border-box;
}

.space-card:hover {
  border-color: var(--app-text-faint);
  box-shadow: 0 8px 20px -4px rgba(15, 23, 42, 0.07);
  transform: translateY(-2px);
}

.space-card.is-skeleton {
  cursor: default;
  transform: none;
}

.space-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 0.7rem;
  min-width: 0;
}

.space-icon-box {
  width: 36px;
  height: 36px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* 空间图标配色由 spaceColors.js 按 id 哈希内联，无需类名 */

.space-card-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--app-text);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.space-more-btn {
  cursor: pointer;
  color: var(--app-text-faint);
  padding: 4px 6px;
  border-radius: 4px;
  transition: all 0.15s;
  outline: none;
  flex-shrink: 0;
}

.space-card:hover .space-more-btn {
  color: var(--app-text-faint);
}

.space-more-btn:hover {
  color: var(--app-text) !important;
  background-color: var(--app-hover);
}

.space-card-desc {
  font-size: 0.83rem;
  color: var(--app-text-muted);
  margin: 0 0 auto;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  word-break: break-word;
}

/* 身份徽章 */
.role-chip {
  font-size: 0.68rem;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  flex-shrink: 0;
  white-space: nowrap;
}

.role-owner { background: #fef3c7; color: #b45309; }
.role-admin { background: #e0e7ff; color: #4338ca; }
.role-member { background: var(--app-hover); color: var(--app-text-muted); }

/* 空间标签行 */
.space-card-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0.55rem 0 0;
}

.space-tag-chip {
  font-size: 0.68rem;
  font-weight: 500;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid transparent;
  white-space: nowrap;
}

.space-tag-more {
  font-size: 0.68rem;
  color: var(--app-text-faint);
}

.space-card-foot {
  border-top: 1px solid var(--app-border-soft);
  padding-top: 0.65rem;
  margin-top: 0.9rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.space-card-date {
  font-size: 0.75rem;
  color: var(--app-text-faint);
}

.space-card-members {
  font-size: 0.75rem;
  color: var(--app-text-faint);
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.foot-stat {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.space-card-dashed {
  border: 1.5px dashed var(--app-border);
  border-radius: 12px;
  min-height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  color: var(--app-text-muted);
  font-size: 0.9rem;
  font-weight: 500;
  transition: all 0.15s ease;
  box-sizing: border-box;
}

.space-card-dashed:hover {
  border-color: var(--app-accent);
  color: var(--app-accent);
  background: #fbfcff;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.danger-item {
  color: #ef4444;
}

/* ===== 移动端适配 (置于文件末尾保证覆盖前面的 1100px 断点) ===== */
@media (max-width: 768px) {
  .home-page {
    padding: 1.1rem 1rem 2rem;
  }

  .greeting-title { font-size: 1.3rem; }

  .stats-row {
    grid-template-columns: repeat(2, 1fr);
    gap: 0.6rem;
  }

  .stat-card {
    padding: 0.8rem 0.85rem;
    gap: 10px;
  }

  .stat-icon-box {
    width: 36px;
    height: 36px;
    border-radius: 10px;
  }

  .stat-label { display: none; }
  .stat-arrow { display: none; }

  .recent-grid { grid-template-columns: 1fr; }
  .space-grid { grid-template-columns: 1fr; }
}
</style>
