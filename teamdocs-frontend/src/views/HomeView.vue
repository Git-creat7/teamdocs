<template>
  <div class="home-page">
    <div class="home-container">
      <!-- 问候区：白色简洁版 -->
      <div class="greeting-block anim-item" style="--delay: 0">
        <div class="greeting-text">
          <h1 class="greeting-title">{{ greeting }}，{{ displayName }}</h1>
          <p class="greeting-sub">{{ todayText }}</p>
        </div>
        <div v-if="!isEmpty" class="greeting-actions">
          <el-button type="primary" class="greet-btn" @click="editVisible = true; editingSpace = null; resetForm()">
            <el-icon><Plus /></el-icon>
            新建空间
          </el-button>
          <el-button class="greet-btn" @click="router.push('/recent')">
            继续上次浏览
          </el-button>
        </div>
      </div>

      <!-- 新手引导进度横幅：有空间但三步未走完时显示 -->
      <OnboardingBanner
        v-if="!spacesLoading"
        :spaces="spaces"
        class="anim-item"
        style="--delay: 1"
      />

      <!-- 空态：三步引导 -->
      <div v-if="isEmpty" class="onboard-card anim-item" style="--delay: 1">
        <h2 class="onboard-title">三步开始团队协作</h2>
        <p class="onboard-sub">创建您的第一个空间，将文档集中管理，与团队成员无缝协作。</p>

        <div class="onboard-steps">
          <div class="onboard-step is-active">
            <div class="step-dot">1</div>
            <div class="step-name">创建空间</div>
            <div class="step-desc">建立独立的团队或项目工作区</div>
          </div>
          <div class="step-line"></div>
          <div class="onboard-step">
            <div class="step-dot">2</div>
            <div class="step-name">上传文档</div>
            <div class="step-desc">上传 PDF, Markdown 或其他格式文件</div>
          </div>
          <div class="step-line"></div>
          <div class="onboard-step">
            <div class="step-dot">3</div>
            <div class="step-name">邀请成员</div>
            <div class="step-desc">邀请成员加入空间共同编辑</div>
          </div>
        </div>

        <el-button
          type="primary"
          size="large"
          class="onboard-cta"
          @click="editVisible = true; editingSpace = null; resetForm()"
        >
          创建第一个空间
        </el-button>
      </div>

      <!-- 继续阅读：最近一次看的文档 -->
      <article
        v-if="!isEmpty && resumeDoc"
        class="resume-card anim-item"
        style="--delay: 1"
      >
        <FileIcon :ext="getFileExt(resumeDoc.name, resumeDoc.fileType)" :size="30" />
        <div class="resume-body">
          <span class="resume-name">{{ resumeDoc.name }}</span>
          <span class="resume-meta">上次看到 {{ formatDateTime(resumeDoc.lastViewedAt) }} · {{ resumeDoc.spaceName }}</span>
        </div>
        <el-button type="primary" class="resume-btn" @click="openRecentDoc(resumeDoc)">
          继续阅读
        </el-button>
      </article>

      <!-- 快捷动作入口：Notion 式粉彩底卡片 -->
      <div v-if="!isEmpty" class="stats-row anim-item" style="--delay: 2">
        <button type="button" class="stat-card is-clickable tint-sky" @click="goSpacePanel('members')">
          <div class="stat-icon-box">
            <el-icon :size="22"><User /></el-icon>
          </div>
          <div class="stat-body">
            <span class="stat-value-text">添加成员</span>
            <span class="stat-label">邀请伙伴进空间协作</span>
          </div>
          <el-icon class="stat-arrow"><ChevronRight /></el-icon>
        </button>

        <button type="button" class="stat-card is-clickable tint-lavender" @click="router.push('/tags')">
          <div class="stat-icon-box">
            <el-icon :size="22"><Tag /></el-icon>
          </div>
          <div class="stat-body">
            <span class="stat-value-text">标签管理</span>
            <span class="stat-label">为文档建立分类体系</span>
          </div>
          <el-icon class="stat-arrow"><ChevronRight /></el-icon>
        </button>

        <button type="button" class="stat-card is-clickable tint-peach" @click="goTrash">
          <div class="stat-icon-box">
            <el-icon :size="22"><Trash2 /></el-icon>
          </div>
          <div class="stat-body">
            <span class="stat-value-text">回收站</span>
            <span class="stat-label">已删文档可恢复</span>
          </div>
          <el-icon class="stat-arrow"><ChevronRight /></el-icon>
        </button>

        <button type="button" class="stat-card is-clickable tint-mint" @click="router.push('/recent')">
          <div class="stat-icon-box">
            <el-icon :size="22"><Clock /></el-icon>
          </div>
          <div class="stat-body">
            <span class="stat-value-text">最近浏览</span>
            <span class="stat-label">继续上次看的文档</span>
          </div>
          <el-icon class="stat-arrow"><ChevronRight /></el-icon>
        </button>
      </div>

      <!-- 最近浏览 + 团队动态 双栏 -->
      <div v-if="!isEmpty" class="home-columns anim-item" style="--delay: 3">
        <section class="home-section col-main">
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
            <button
              v-for="doc in recentDocs.slice(0, 4)"
              :key="doc.documentId"
              type="button"
              class="recent-card"
              @click="openRecentDoc(doc)"
            >
              <div class="recent-card-top">
                <div
                  class="recent-icon-box"
                  :style="{ background: fileTintBg(getFileExt(doc.name, doc.fileType)) }"
                >
                  <FileIcon :ext="getFileExt(doc.name, doc.fileType)" :size="26" />
                </div>
                <span class="recent-time-pill">{{ formatRelativeTime(doc.lastViewedAt) }}</span>
              </div>
              <span class="recent-card-name" :title="doc.name">{{ doc.name }}</span>
              <span class="recent-space-name">{{ doc.spaceName }}</span>
            </button>
          </div>
        </section>

        <section class="home-section col-side">
          <div class="section-head">
            <h2 class="section-title activity-title">
              团队动态
              <el-icon :size="16" class="activity-title-icon"><UsersRound /></el-icon>
            </h2>
          </div>

          <div class="activity-viewport">
            <div class="activity-scroll">
          <div v-if="loadingActivities" class="activity-list">
            <el-skeleton :rows="5" animated />
          </div>

          <EmptyState
            v-else-if="activities.length === 0"
            :icon="UsersRound"
            title="还没有团队动态"
            description="空间里的操作会出现在这里"
          />

          <div v-else class="activity-list">
            <div v-for="act in activities" :key="act.id" class="activity-item">
              <el-avatar
                v-if="!act.avatar"
                :size="30"
                class="activity-avatar"
                :style="{ background: avatarColor(act.username) }"
              >
                {{ (act.username || 'U').charAt(0).toUpperCase() }}
              </el-avatar>
              <el-avatar v-else :size="30" class="activity-avatar" :src="act.avatar" />
              <div class="activity-body">
                <p class="activity-text">
                  <span class="activity-user">{{ act.username }}</span>
                  {{ activityVerb(act) }}
                  <template v-if="activityName(act)">
                    <button
                      v-if="canOpenActivityDocument(act)"
                      type="button"
                      class="activity-doc"
                      @click="openActivityDoc(act)"
                    >{{ truncateText(activityName(act), 30) }}</button>
                    <span
                      v-else-if="activityMeta(act).style === 'strong'"
                      class="activity-strong"
                    >{{ truncateText(activityName(act), 30) }}</span>
                    <span
                      v-else-if="shouldWrapActivityName(act) || activityMeta(act).style === 'quote'"
                      class="activity-quote"
                    >“{{ truncateText(activityName(act), 42) }}”</span>
                    <span
                      v-else
                      class="activity-plain"
                    >{{ truncateText(activityName(act), 30) }}</span>
                    <template v-if="activityMeta(act).suffix">{{ activityMeta(act).suffix }}</template>
                  </template>
                  <template v-if="act.spaceName"> · {{ act.spaceName }}</template>
                </p>
              </div>
              <span class="activity-time">{{ formatRelativeTime(act.createdAt) }}</span>
            </div>
          </div>
            </div>
          </div>
        </section>
      </div>

      <!-- 我的空间 -->
      <section v-if="!isEmpty" class="home-section anim-item" style="--delay: 4">
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
          <article
            v-for="space in spaces"
            :key="space.id"
            class="space-card"
          >
            <RouterLink
              class="space-card-main"
              :to="`/spaces/${space.id}`"
              :aria-label="`打开空间：${space.name}`"
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
            </RouterLink>
            <el-dropdown
              class="space-card-actions"
              trigger="click"
              @command="(cmd) => handleSpaceCommand(cmd, space)"
            >
              <button
                type="button"
                class="space-more-btn"
                :aria-label="`更多操作：${space.name}`"
              >
                <el-icon><MoreHorizontal /></el-icon>
              </button>
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
          </article>

          <!-- 新建空间虚线卡片 -->
          <button
            type="button"
            class="space-card-dashed"
            @click="editVisible = true; editingSpace = null; resetForm()"
          >
            <el-icon :size="20"><Plus /></el-icon>
            <span>创建新空间</span>
          </button>
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
        <button
          v-for="space in spaces"
          :key="space.id"
          type="button"
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
        </button>
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
import { FolderOpen, MoreHorizontal, Pencil, Trash2, Plus, Clock, ChevronRight, User, Tag, History, FileText, UsersRound } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import FileIcon from '@/components/FileIcon.vue'
import OnboardingBanner from '@/components/OnboardingBanner.vue'
import { getRecentDocumentsApi } from '@/api/user'
import { getActivitiesApi } from '@/api/activity'
import { createSpaceApi, updateSpaceApi, deleteSpaceApi } from '@/api/space'
import { listTagsApi } from '@/api/tag'
import { storeToRefs } from 'pinia'
import { useUserStore, useSpacesStore } from '@/stores'
import { formatDateTime, formatRelativeTime, getFileExt, getFileTypeColor } from '@/utils/format'
import { tagStyle } from '@/utils/tagColors'
import { spaceIconPalette } from '@/utils/spaceColors'
import { avatarColor } from '@/utils/userColors'
import { activityMeta, activityName, activityVerb, canOpenActivityDocument, shouldWrapActivityName, truncateText } from '@/utils/activityText'
import { useDocumentNavigation } from '@/composables/useDocumentNavigation'

const router = useRouter()
const { openDocument } = useDocumentNavigation()
const spacesStore = useSpacesStore()
const { spaces, loading: spacesLoading } = storeToRefs(spacesStore)
const refreshSpaces = spacesStore.refresh
const { userInfo } = storeToRefs(useUserStore())

const recentDocs = ref([])
const loadingRecent = ref(true)

const activities = ref([])
const loadingActivities = ref(true)

// 空态 = 加载完成且一个空间都没有 → 显示三步引导
const isEmpty = computed(() => !spacesLoading.value && spaces.value.length === 0)

// 继续阅读卡取最近一条浏览记录
const resumeDoc = computed(() => recentDocs.value[0] || null)

function openActivityDoc(act) {
  if (!canOpenActivityDocument(act) || !act.spaceId) return
  openDocument({
    spaceId: act.spaceId,
    documentId: act.resourceId
  })
}

// 文件图标底色：按类型色打 12% 透明浅底
function fileTintBg(ext) {
  const c = getFileTypeColor(ext)
  return `color-mix(in srgb, ${c} 12%, transparent)`
}

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
  loadActivities()
  // SWR：立即用 store 里的旧数据渲染，同时后台刷新 (上传/加成员后的数字不再说谎)
  refreshSpaces()
})

async function loadActivities() {
  loadingActivities.value = true
  try {
    activities.value = await getActivitiesApi(20)
  } catch (err) {
    activities.value = []
  } finally {
    loadingActivities.value = false
  }
}

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
  openDocument({
    spaceId: doc.spaceId,
    documentId: doc.documentId
  })
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

/* ===== 空态：三步引导卡 ===== */
.onboard-card {
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 16px;
  padding: 3rem 2.5rem 2.75rem;
  text-align: center;
  margin-bottom: 2.25rem;
}

.onboard-title {
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0 0 0.5rem;
}

.onboard-sub {
  font-size: 0.9rem;
  color: var(--app-text-muted);
  margin: 0 0 2.5rem;
}

.onboard-steps {
  display: flex;
  align-items: stretch;
  justify-content: center;
  gap: 0;
  max-width: 860px;
  margin: 0 auto 2.5rem;
}

.onboard-step {
  flex: 1;
  min-width: 0;
  padding: 1.4rem 1rem;
  border-radius: 14px;
  border: 1.5px solid transparent;
}

.onboard-step.is-active {
  border-color: var(--app-accent);
  background: var(--app-panel);
  box-shadow: 0 10px 26px -12px rgba(37, 99, 235, 0.28);
}

.step-dot {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  margin: 0 auto 1rem;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.1rem;
  font-weight: 700;
  background: var(--app-hover);
  color: var(--app-text-faint);
}

.onboard-step.is-active .step-dot {
  background: var(--app-accent);
  color: #fff;
}

.step-name {
  font-size: 0.92rem;
  font-weight: 600;
  color: var(--app-text-faint);
  margin-bottom: 0.4rem;
}

.onboard-step.is-active .step-name {
  color: var(--app-accent);
}

.step-desc {
  font-size: 0.8rem;
  line-height: 1.5;
  color: var(--app-text-faint);
}

.onboard-step.is-active .step-desc {
  color: var(--app-text);
}

.step-line {
  flex-shrink: 0;
  width: 72px;
  height: 1px;
  background: var(--app-border);
  align-self: center;
}

.onboard-cta {
  min-width: 200px;
  height: 46px;
  border-radius: 10px;
  font-weight: 600;
}

/* ===== 继续阅读卡 ===== */
.resume-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 14px;
  padding: 1rem 1.25rem;
  margin-bottom: 1.25rem;
  transition: transform var(--dur-fast) var(--ease-standard),
              box-shadow var(--dur-fast) var(--ease-standard),
              border-color var(--dur-fast) var(--ease-standard);
}

.resume-card:hover {
  transform: translateY(-2px);
  border-color: var(--app-text-faint);
  box-shadow: 0 8px 20px -6px rgba(15, 23, 42, 0.1);
}

.resume-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.resume-name {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resume-meta {
  font-size: 0.78rem;
  color: var(--app-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resume-btn {
  flex-shrink: 0;
  border-radius: 8px;
  font-weight: 500;
}

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
  width: 100%;
  appearance: none;
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 14px;
  padding: 1.1rem 1.2rem;
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
  color: inherit;
  font: inherit;
  text-align: left;
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

.stat-card.is-clickable:focus-visible,
.recent-card:focus-visible,
.space-pick-item:focus-visible,
.activity-doc:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
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
  width: 100%;
  appearance: none;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
  text-align: left;
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

/* ===== 双栏：最近浏览与团队动态对等分栏 ===== */
.home-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2rem;
  margin-bottom: 2.25rem;
}

.home-columns .home-section {
  margin-bottom: 0;
}

/* 最近浏览卡片 */
.recent-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0.9rem;
}

.recent-card {
  width: 100%;
  appearance: none;
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 10px;
  padding: 0.9rem 1rem;
  cursor: pointer;
  transition: transform var(--dur-fast) var(--ease-standard),
              box-shadow var(--dur-fast) var(--ease-standard),
              border-color var(--dur-fast) var(--ease-standard);
  min-width: 0;
  color: inherit;
  font: inherit;
  text-align: left;
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
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 0.7rem;
}

.recent-icon-box {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.recent-time-pill {
  font-size: 0.7rem;
  color: var(--app-text-faint);
  background: var(--app-hover);
  border-radius: 999px;
  padding: 2px 9px;
  flex-shrink: 0;
}

.recent-card-name {
  display: block;
  font-size: 0.87rem;
  font-weight: 600;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 0.25rem;
}

.recent-space-name {
  display: block;
  font-size: 0.75rem;
  color: var(--app-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ===== 团队动态 ===== */
.activity-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.activity-title-icon {
  color: var(--app-text-faint);
}

/* 右栏高度跟随左栏：滚动区绝对定位、不参与 grid 行高计算，
   动态再多也只在左栏撑出的高度内滚动，两栏底边恒对齐 */
.col-side {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.activity-viewport {
  flex: 1;
  position: relative;
  min-height: 220px;
}

.activity-scroll {
  position: absolute;
  inset: 0;
  overflow-y: auto;
  padding-right: 6px;
  scrollbar-width: thin;
}

.activity-scroll::-webkit-scrollbar {
  width: 5px;
}

.activity-scroll::-webkit-scrollbar-thumb {
  background: var(--app-border);
  border-radius: 999px;
}

.activity-list {
  display: flex;
  flex-direction: column;
}

.activity-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 0.6rem 0;
}

.activity-item + .activity-item {
  border-top: 1px solid var(--app-border-soft);
}

.activity-avatar {
  flex-shrink: 0;
  color: #fff;
  font-size: 0.78rem;
  font-weight: 600;
}

.activity-body {
  flex: 1;
  min-width: 0;
}

.activity-text {
  margin: 0;
  font-size: 0.82rem;
  line-height: 1.55;
  color: var(--app-text-muted);
  word-break: break-word;
}

.activity-user {
  font-weight: 600;
  color: var(--app-text);
}

.activity-doc {
  appearance: none;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--app-accent);
  cursor: pointer;
  font: inherit;
}

.activity-doc:hover {
  text-decoration: underline;
}

.activity-strong {
  font-weight: 600;
  color: var(--app-text);
}

.activity-quote {
  color: var(--app-text-muted);
}

.activity-time {
  flex-shrink: 0;
  font-size: 0.72rem;
  color: var(--app-text-faint);
  padding-top: 3px;
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
  transition: transform var(--dur-fast) var(--ease-standard),
              box-shadow var(--dur-fast) var(--ease-standard),
              border-color var(--dur-fast) var(--ease-standard);
  display: flex;
  flex-direction: column;
  min-height: 140px;
  box-sizing: border-box;
  position: relative;
}

.space-card:hover {
  border-color: var(--app-text-faint);
  box-shadow: 0 8px 20px -4px rgba(15, 23, 42, 0.07);
  transform: translateY(-2px);
}

.space-card.is-skeleton {
  padding: 1.2rem 1.3rem;
  cursor: default;
  transform: none;
}

.space-card-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  padding: 1.2rem 1.3rem;
  border-radius: 11px;
  color: inherit;
  text-decoration: none;
}

.space-card-main:focus-visible,
.space-more-btn:focus-visible,
.space-card-dashed:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.space-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 0.7rem;
  min-width: 0;
  padding-right: 34px;
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

.space-card-actions {
  position: absolute;
  top: calc(1.2rem + 4px);
  right: 1.3rem;
}

.space-more-btn {
  appearance: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--app-text-faint);
  padding: 4px 6px;
  border-radius: 4px;
  transition: all 0.15s;
  font: inherit;
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
  width: 100%;
  appearance: none;
  border: 1.5px dashed var(--app-border);
  background: transparent;
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
  font-family: inherit;
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

  .home-columns {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .home-columns .col-main { margin-bottom: 2.25rem; }

  /* 单列堆叠时右栏没有"左栏高度"可跟随，还原为固定高度内滚 */
  .activity-viewport { min-height: 0; }

  .activity-scroll {
    position: static;
    max-height: 380px;
  }

  .onboard-card { padding: 2rem 1.25rem; }

  .onboard-steps {
    flex-direction: column;
    gap: 0.9rem;
    margin-bottom: 2rem;
  }

  .step-line {
    width: 1px;
    height: 24px;
    align-self: center;
  }

  .resume-card { flex-wrap: wrap; }
  .resume-body { flex-basis: 60%; }
}
</style>
