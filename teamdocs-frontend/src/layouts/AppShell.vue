<template>
  <div class="shell-root">
    <!-- 移动端抽屉遮罩 -->
    <div
      v-if="isMobile && mobileSidebarOpen"
      class="sidebar-mask"
      @click="mobileSidebarOpen = false"
    ></div>

    <!-- ===== 左侧侧栏 (桌面常驻可折叠 / 移动端抽屉) ===== -->
    <aside :class="['shell-sidebar', { collapsed: collapsed && !isMobile, 'mobile-open': mobileSidebarOpen }]">
      <!-- Logo 区：品牌名 + 副标题 -->
      <div class="sidebar-brand" @click="router.push('/home')">
        <div class="brand-logo">
          <svg viewBox="0 0 24 24" fill="none" class="brand-svg">
            <path d="M19 3H9C7.89543 3 7 3.89543 7 5V19C7 20.1046 7.89543 21 9 21H19C20.1046 21 21 20.1046 21 19V5C21 3.89543 20.1046 3 19 3Z" stroke="white" stroke-width="2"/>
            <path d="M3 7V17C3 18.1046 3.89543 19 5 19" stroke="white" stroke-width="2"/>
          </svg>
        </div>
        <div v-show="!collapsed" class="brand-texts">
          <span class="brand-text">TeamDocs</span>
          <span class="brand-sub">Document Hub</span>
        </div>
      </div>

      <!-- 主导航 -->
      <nav class="sidebar-nav">
        <div
          :class="['nav-item', { active: route.path === '/home' }]"
          @click="router.push('/home')"
        >
          <el-icon class="nav-icon"><Home /></el-icon>
          <span v-show="!collapsed" class="nav-label">首页</span>
        </div>
        <div
          :class="['nav-item', { active: route.path === '/recent' }]"
          @click="router.push('/recent')"
        >
          <el-icon class="nav-icon"><Clock /></el-icon>
          <span v-show="!collapsed" class="nav-label">最近浏览</span>
        </div>
        <div
          :class="['nav-item', { active: route.path === '/activities' }]"
          @click="router.push('/activities')"
        >
          <el-icon class="nav-icon"><UsersRound /></el-icon>
          <span v-show="!collapsed" class="nav-label">团队动态</span>
        </div>
        <div class="nav-item" @click="goManageTags">
          <el-icon class="nav-icon"><Tag /></el-icon>
          <span v-show="!collapsed" class="nav-label">标签管理</span>
        </div>
        <div
          :class="['nav-item', { active: route.path === '/trash' }]"
          @click="goTrash"
        >
          <el-icon class="nav-icon"><Trash2 /></el-icon>
          <span v-show="!collapsed" class="nav-label">回收站</span>
        </div>
      </nav>

      <!-- 主动作：上传文档 -->
      <div v-show="!collapsed" class="sidebar-cta">
        <button type="button" class="upload-cta" @click="goUpload">
          <Plus :size="16" :stroke-width="2.4" />
          上传文档
        </button>
      </div>

      <!-- 空间列表 -->
      <div class="sidebar-spaces">
        <div v-show="!collapsed" class="spaces-header">
          <span class="spaces-title">我的空间</span>
          <el-tooltip content="新建空间" placement="right">
            <el-icon class="spaces-add-btn" @click="createSpaceVisible = true"><Plus /></el-icon>
          </el-tooltip>
        </div>

        <div class="spaces-list">
          <div v-for="(space, index) in spaces" :key="space.id" class="space-group">
            <el-tooltip
              :content="space.name"
              placement="right"
              :disabled="!collapsed"
            >
              <div
                :class="['space-item', { active: activeSpaceId === space.id }]"
                @click="router.push(`/spaces/${space.id}`)"
              >
                <span class="space-bullet" :style="{ backgroundColor: spaceDotColor(space.id) }"></span>
                <span v-show="!collapsed" class="space-item-name">{{ space.name }}</span>
                <el-icon
                  v-show="!collapsed"
                  :class="['space-expand-icon', { expanded: expandedSpaceIds.has(space.id) }]"
                  @click.stop="toggleSpaceExpand(space.id)"
                >
                  <ChevronRight />
                </el-icon>
              </div>
            </el-tooltip>

            <!-- 展开的直达入口：成员 / 标签 / 回收站 -->
            <div v-if="!collapsed && expandedSpaceIds.has(space.id)" class="space-sublinks">
              <div class="space-sublink" @click="goSpacePanel(space.id, 'members')">
                <el-icon><User /></el-icon>
                <span>成员</span>
              </div>
              <div class="space-sublink" @click="goSpacePanel(space.id, 'tags')">
                <el-icon><Tag /></el-icon>
                <span>标签</span>
              </div>
              <div class="space-sublink" @click="router.push({ path: '/trash', query: { spaceId: space.id } })">
                <el-icon><Trash2 /></el-icon>
                <span>回收站</span>
              </div>
            </div>
          </div>

          <div v-if="!spacesLoading && spaces.length === 0 && !collapsed" class="spaces-empty">
            还没有空间
            <el-button link type="primary" size="small" @click="createSpaceVisible = true">
              去创建
            </el-button>
          </div>
        </div>

        <!-- 最近文档迷你分组：最快回到工作的入口 -->
        <div v-if="!collapsed && sidebarRecentDocs.length > 0" class="sidebar-recent">
          <div class="spaces-header">
            <span class="spaces-title">最近文档</span>
          </div>
          <button
            v-for="doc in sidebarRecentDocs"
            :key="doc.documentId"
            type="button"
            class="recent-doc-item"
            :title="doc.name"
            @click="openSidebarRecentDoc(doc)"
          >
            <FileIcon :ext="getFileExt(doc.name, doc.fileType)" :size="18" />
            <span class="recent-doc-name">{{ middleEllipsis(doc.name, 18) }}</span>
          </button>
        </div>
      </div>

      <!-- 底部：我的身份卡 + 折叠 -->
      <div class="sidebar-footer">
        <div v-if="!collapsed && userInfo" class="identity-card">
          <el-avatar :size="30" :src="userInfo.avatar || undefined" class="identity-avatar">
            {{ (userInfo.nickname || userInfo.username || 'U').charAt(0).toUpperCase() }}
          </el-avatar>
          <div class="identity-info">
            <span class="identity-name">{{ userInfo.nickname || userInfo.username }}</span>
            <span class="identity-sub">{{ spaces.length }} 个空间</span>
          </div>
        </div>
        <div class="nav-item" @click="collapsed = !collapsed">
          <el-icon class="nav-icon">
            <PanelLeftOpen v-if="collapsed" />
            <PanelLeftClose v-else />
          </el-icon>
          <span v-show="!collapsed" class="nav-label">收起侧栏</span>
        </div>
      </div>
    </aside>

    <!-- ===== 右侧主区 ===== -->
    <div class="shell-main">
      <!-- 顶栏：全局搜索 + 用户 -->
      <header class="shell-topbar">
        <button
          v-if="isMobile"
          type="button"
          class="hamburger-btn"
          title="打开菜单"
          @click="mobileSidebarOpen = true"
        >
          <Menu :size="20" />
        </button>
        <!-- 左：搜索范围空间选择器 (带彩色圆点) -->
        <el-select
          v-model="searchSpaceId"
          class="search-scope-select"
          placeholder="范围"
          :disabled="spaces.length === 0"
        >
          <template #prefix>
            <span
              class="scope-dot"
              :style="{ backgroundColor: searchSpaceId ? spaceDotColor(searchSpaceId) : '#2563eb' }"
            ></span>
          </template>
          <el-option label="全部空间" :value="0" />
          <el-option
            v-for="space in spaces"
            :key="space.id"
            :label="space.name"
            :value="space.id"
          />
        </el-select>

        <!-- 中：全局搜索 -->
        <div class="topbar-search">
          <el-input
            v-model.trim="searchKeyword"
            class="global-search-input"
            placeholder="搜索文档名 / 标签…"
            clearable
            @keyup.enter="handleGlobalSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>

        <div class="topbar-right">
          <UserMenu />
        </div>
      </header>

      <!-- 路由页面 (path 变化触发转场，仅 query 变化不重挂) -->
      <main class="shell-content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" :key="route.path" />
          </transition>
        </router-view>
      </main>
    </div>

    <!-- 全部空间搜索结果 -->
    <el-dialog
      v-model="globalSearchVisible"
      :title="`搜索 “${globalKeyword}” — 全部空间`"
      width="560px"
      destroy-on-close
    >
      <div v-loading="globalSearching" class="global-result-list">
        <EmptyState
          v-if="!globalSearching && globalResults.length === 0"
          :icon="SearchX"
          title="所有空间里都没有找到匹配的文档"
          description="搜索会匹配各空间的文档名与标签"
        />
        <div
          v-for="doc in globalResults"
          :key="`${doc.spaceId}-${doc.id}`"
          class="global-result-row"
          @click="openGlobalResult(doc)"
        >
          <FileIcon :ext="getFileExt(doc.name, doc.fileType)" :size="30" />
          <div class="gr-main">
            <span class="gr-name" :title="doc.name">{{ doc.name }}</span>
            <span class="gr-meta">{{ doc.spaceName }} · {{ formatDateTime(doc.updatedAt) }}</span>
          </div>
          <el-icon class="gr-arrow"><ChevronRight /></el-icon>
        </div>
      </div>
    </el-dialog>

    <!-- 新建空间对话框 (壳层级，任何页面可唤起) -->
    <el-dialog
      v-model="createSpaceVisible"
      title="新建空间"
      width="420px"
      destroy-on-close
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
      >
        <el-form-item label="空间名称" prop="name">
          <el-input
            v-model.trim="createForm.name"
            placeholder="请输入空间名称"
            maxlength="64"
            clearable
          />
        </el-form-item>
        <el-form-item label="空间描述" prop="description">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="请输入空间描述（可选）"
            maxlength="255"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="createSpaceVisible = false">取消</el-button>
          <el-button type="primary" :loading="creatingSpace" @click="handleCreateSpace">
            创建
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Home,
  Clock,
  Trash2,
  Plus,
  Search,
  PanelLeftOpen,
  PanelLeftClose,
  ChevronRight,
  User,
  Tag,
  FileText,
  SearchX,
  Menu,
  UsersRound
} from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import { createSpaceApi } from '@/api/space'
import { searchDocumentsApi } from '@/api/document'
import { storeToRefs } from 'pinia'
import { useUserStore, useSpacesStore } from '@/stores'
import UserMenu from '@/components/UserMenu.vue'
import FileIcon from '@/components/FileIcon.vue'
import { formatDateTime, getFileExt, middleEllipsis, buildRecentDocRoute } from '@/utils/format'
import { spaceDotColor } from '@/utils/spaceColors'
import { getRecentDocumentsApi } from '@/api/user'

const route = useRoute()
const router = useRouter()

const spacesStore = useSpacesStore()
const userStore = useUserStore()
const { spaces, loading: spacesLoading } = storeToRefs(spacesStore)
const { userInfo } = storeToRefs(userStore)
const refreshSpaces = spacesStore.refresh
const refreshUser = userStore.refresh

const collapsed = ref(localStorage.getItem('teamdocs_sidebar_collapsed') === '1')
watch(collapsed, (val) => {
  localStorage.setItem('teamdocs_sidebar_collapsed', val ? '1' : '0')
})

// 移动端：侧栏变抽屉，汉堡键唤起，导航后自动收起
const mq = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mq.matches)
mq.addEventListener('change', (e) => { isMobile.value = e.matches })
const mobileSidebarOpen = ref(false)

watch(() => route.fullPath, () => {
  mobileSidebarOpen.value = false
})

// 当前激活的空间 (用于侧栏高亮)
const activeSpaceId = computed(() => {
  const raw = route.params.spaceId
  const num = Number(raw)
  return isNaN(num) ? null : num
})

// 全局搜索：0 = 全部空间 (并发搜所有空间后合并)，其余为指定空间 (跳工作台搜索态)
const searchSpaceId = ref(0)
const searchKeyword = ref('')

// 空间直达入口的展开状态 (必须先于下面 immediate watch 声明，否则 TDZ 报错)
const expandedSpaceIds = ref(new Set())

watch(activeSpaceId, (id) => {
  if (id) {
    searchSpaceId.value = id
    // 进入某空间时自动展开它的直达入口
    if (!expandedSpaceIds.value.has(id)) {
      expandedSpaceIds.value = new Set([...expandedSpaceIds.value, id])
    }
  } else {
    searchSpaceId.value = 0
  }
}, { immediate: true })

function toggleSpaceExpand(id) {
  const next = new Set(expandedSpaceIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expandedSpaceIds.value = next
}

function goSpacePanel(id, panel) {
  router.push({ path: `/spaces/${id}`, query: { panel, t: Date.now() } })
}

// 全部空间搜索结果
const globalSearchVisible = ref(false)
const globalSearching = ref(false)
const globalResults = ref([])
const globalKeyword = ref('')

async function handleGlobalSearch() {
  const keyword = searchKeyword.value.trim()
  if (!keyword) return
  if (spaces.value.length === 0) {
    ElMessage.warning('还没有空间，先创建一个吧')
    return
  }

  // 指定单空间：跳该空间工作台的搜索态；跳转后清空输入框避免跨页残留
  if (searchSpaceId.value !== 0) {
    router.push({
      path: `/spaces/${searchSpaceId.value}`,
      query: { search: keyword, t: Date.now() }
    })
    searchKeyword.value = ''
    return
  }

  // 全部空间：并发搜每个空间后合并展示
  globalKeyword.value = keyword
  globalSearchVisible.value = true
  globalSearching.value = true
  try {
    const settled = await Promise.all(
      spaces.value.map(async (s) => {
        try {
          const page = await searchDocumentsApi(s.id, keyword, 1, 50)
          return page.records.map((r) => ({ ...r, spaceName: s.name }))
        } catch (err) {
          return []
        }
      })
    )
    globalResults.value = settled
      .flat()
      .sort((a, b) => new Date(b.updatedAt || 0) - new Date(a.updatedAt || 0))
  } finally {
    globalSearching.value = false
  }
}

function openGlobalResult(doc) {
  globalSearchVisible.value = false
  searchKeyword.value = ''
  router.push({
    path: `/spaces/${doc.spaceId}`,
    query: { search: globalKeyword.value, highlight: doc.id, t: Date.now() }
  })
}

function goTrash() {
  // 回收站是空间维度的：优先带上当前空间，否则用第一个空间
  const sid = activeSpaceId.value || searchSpaceId.value || spaces.value[0]?.id
  if (!sid) {
    ElMessage.warning('还没有空间，先创建一个吧')
    return
  }
  router.push({ path: '/trash', query: { spaceId: sid } })
}

// 主导航「标签管理」：进当前空间 (无上下文时用第一个空间) 并打开标签面板
function goManageTags() {
  const sid = activeSpaceId.value || searchSpaceId.value || spaces.value[0]?.id
  if (!sid) {
    ElMessage.warning('还没有空间，先创建一个吧')
    return
  }
  goSpacePanel(sid, 'tags')
}

// 侧栏主动作：进当前空间工作台并直接唤起文件选择
function goUpload() {
  const sid = activeSpaceId.value || searchSpaceId.value || spaces.value[0]?.id
  if (!sid) {
    ElMessage.warning('还没有空间，先创建一个吧')
    return
  }
  router.push({ path: `/spaces/${sid}`, query: { upload: 1, t: Date.now() } })
}

// 新建空间
const createSpaceVisible = ref(false)
const creatingSpace = ref(false)
const createFormRef = ref(null)
const createForm = reactive({ name: '', description: '' })

const createRules = {
  name: [
    { required: true, message: '请输入空间名称', trigger: 'blur' },
    { min: 1, max: 64, message: '空间名称长度在 1 到 64 个字符', trigger: 'blur' }
  ],
  description: [
    { max: 255, message: '空间描述最长 255 个字符', trigger: 'blur' }
  ]
}

async function handleCreateSpace() {
  if (!createFormRef.value) return
  try {
    await createFormRef.value.validate()
  } catch (err) {
    return
  }
  creatingSpace.value = true
  try {
    await createSpaceApi({
      name: createForm.name.trim(),
      description: createForm.description ? createForm.description.trim() : ''
    })
    ElMessage.success('空间创建成功')
    createSpaceVisible.value = false
    createForm.name = ''
    createForm.description = ''
    await refreshSpaces()
  } catch (err) {
    // 拦截器处理
  } finally {
    creatingSpace.value = false
  }
}

// 侧栏最近文档 (前 4 条)。SWR：路由变化时后台刷新但保留旧数据渲染，
// 失败也不清列表——将来加骨架屏也只在首载出现，切页不闪
const sidebarRecentDocs = ref([])

async function loadSidebarRecent() {
  try {
    const list = await getRecentDocumentsApi()
    sidebarRecentDocs.value = list.slice(0, 4)
  } catch (err) {
    // 刷新失败保留旧数据
  }
}

watch(() => route.path, loadSidebarRecent)

function openSidebarRecentDoc(doc) {
  router.push(buildRecentDocRoute(doc, ElMessage.info))
}

onMounted(() => {
  refreshUser()
  refreshSpaces()
  loadSidebarRecent()
})
</script>

<style scoped>
.shell-root {
  height: 100vh;
  width: 100%;
  display: flex;
  overflow: hidden;
  background-color: var(--app-bg);
}

/* ===== 侧栏 ===== */
.shell-sidebar {
  width: 232px;
  background: var(--app-panel-soft);
  border-right: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width 0.2s ease;
  overflow: hidden;
}

.shell-sidebar.collapsed {
  width: 60px;
}

.sidebar-brand {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  cursor: pointer;
  flex-shrink: 0;
}

.brand-logo {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.brand-svg {
  width: 18px;
  height: 18px;
}

.brand-texts {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.brand-text {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--app-text);
  letter-spacing: -0.3px;
  white-space: nowrap;
  line-height: 1.15;
}

.brand-sub {
  font-size: 0.72rem;
  color: var(--app-text-faint);
  white-space: nowrap;
}

/* 侧栏主动作按钮 */
.sidebar-cta {
  padding: 6px 12px 10px;
}

.upload-cta {
  width: 100%;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px dashed var(--app-border);
  border-radius: 8px;
  background: none;
  color: var(--app-text-2);
  font-size: 0.85rem;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease-standard),
              color var(--dur-fast) var(--ease-standard),
              background-color var(--dur-fast) var(--ease-standard);
}

.upload-cta:hover {
  border-color: var(--app-accent);
  color: var(--app-accent);
  background: var(--app-accent-weak);
}

.sidebar-nav {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex-shrink: 0;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 10px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--app-text-2);
  transition: background-color var(--dur-fast) var(--ease-standard);
  white-space: nowrap;
  overflow: hidden;
}

.nav-item:hover {
  background-color: var(--app-hover);
}

.nav-item.active {
  background-color: var(--app-accent-weak);
  color: var(--app-accent);
  font-weight: 600;
}

.nav-icon {
  font-size: 17px;
  flex-shrink: 0;
}

.nav-label {
  font-size: 0.875rem;
}

/* 空间区 */
.sidebar-spaces {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 10px 8px 4px;
  border-top: 1px solid var(--app-border-soft);
  margin-top: 6px;
}

.spaces-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px 6px;
}

.spaces-title {
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--app-text-faint);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.spaces-add-btn {
  font-size: 14px;
  color: var(--app-text-faint);
  cursor: pointer;
  padding: 3px;
  border-radius: 4px;
}

.spaces-add-btn:hover {
  color: var(--app-accent);
  background: var(--app-accent-weak);
}

.spaces-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.space-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 34px;
  padding: 0 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease-standard);
  overflow: hidden;
}

.space-item:hover {
  background-color: var(--app-hover);
}

.space-item.active {
  background-color: var(--app-accent-weak);
}

.space-item.active .space-item-name {
  color: var(--app-accent);
  font-weight: 600;
}

/* 空间彩色圆点 (对标图：小圆点而非字母块) */
.space-bullet {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex-shrink: 0;
  margin: 0 6px 0 3px;
}

.space-dot {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.7rem;
  font-weight: 700;
  color: #ffffff;
  flex-shrink: 0;
}

/* 侧栏最近文档 */
.sidebar-recent {
  padding: 8px 8px 4px;
  border-top: 1px solid var(--app-border-soft);
  flex-shrink: 0;
}

.recent-doc-item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  width: 100%;
  padding: 0 10px;
  border: none;
  background: none;
  text-align: left;
  font: inherit;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease-standard);
  overflow: hidden;
}

.recent-doc-item:focus-visible {
  outline: 2px solid #6366f1;
  outline-offset: -2px;
}

.recent-doc-item:hover {
  background-color: var(--app-hover);
}

.recent-doc-item:hover .recent-doc-name {
  color: var(--app-accent);
}

.recent-doc-icon {
  font-size: 13px;
  color: var(--app-text-faint);
  flex-shrink: 0;
}

.recent-doc-name {
  font-size: 0.8rem;
  color: var(--app-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.space-item-name {
  font-size: 0.85rem;
  color: var(--app-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.space-expand-icon {
  font-size: 12px;
  color: var(--app-text-faint);
  flex-shrink: 0;
  padding: 3px;
  border-radius: 4px;
  transition: transform 150ms var(--ease-standard), color var(--dur-fast) var(--ease-standard);
}

.space-expand-icon:hover {
  color: var(--app-text-muted);
  background: #e2e8f0;
}

.space-expand-icon.expanded {
  transform: rotate(90deg);
}

.space-sublinks {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 2px 0 4px;
  animation: sublinks-in 0.18s ease;
}

@keyframes sublinks-in {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.space-sublink {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  padding: 0 10px 0 42px;
  border-radius: 6px;
  font-size: 0.8rem;
  color: var(--app-text-muted);
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s;
}

.space-sublink:hover {
  background-color: var(--app-hover);
  color: var(--app-accent);
}

.space-sublink .el-icon {
  font-size: 13px;
}

.spaces-empty {
  padding: 12px 10px;
  font-size: 0.8rem;
  color: var(--app-text-faint);
  display: flex;
  align-items: center;
  gap: 2px;
}

.sidebar-footer {
  padding: 8px;
  border-top: 1px solid var(--app-border-soft);
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex-shrink: 0;
}

.identity-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--app-hover-soft);
}

.identity-avatar {
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  color: #ffffff;
  font-weight: 600;
  flex-shrink: 0;
}

.identity-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.identity-name {
  font-size: 0.83rem;
  font-weight: 600;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-sub {
  font-size: 0.72rem;
  color: var(--app-text-faint);
}

/* ===== 主区 ===== */
.shell-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.shell-topbar {
  height: 56px;
  background: var(--app-panel);
  border-bottom: 1px solid var(--app-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px;
  flex-shrink: 0;
}

/* 对标图布局：范围选择器独立居左，搜索框独立居中 */
.topbar-search {
  flex: 1;
  display: flex;
  justify-content: center;
  min-width: 0;
}

.search-scope-select {
  width: 150px;
  flex-shrink: 0;
}

.search-scope-select :deep(.el-select__wrapper) {
  border-radius: 8px;
  background: var(--app-panel);
  box-shadow: 0 0 0 1px var(--app-border) inset;
}

.scope-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.global-search-input {
  max-width: 460px;
}

.global-search-input :deep(.el-input__wrapper) {
  border-radius: 999px;
  padding-left: 16px;
  background: var(--app-bg);
  box-shadow: 0 0 0 1px var(--app-border) inset;
}

.global-search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1.5px var(--app-accent) inset;
  background: var(--app-panel);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.shell-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* ===== 移动端适配：侧栏抽屉化 ===== */
.sidebar-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  z-index: 98;
  animation: fade-in 0.2s ease;
}

@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.hamburger-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--app-text-2);
  cursor: pointer;
  flex-shrink: 0;
}

.hamburger-btn:active {
  background: var(--app-hover);
}

@media (max-width: 768px) {
  .shell-sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 99;
    width: 264px;
    transform: translateX(-100%);
    transition: transform 0.28s var(--ease-out-expo, ease);
    box-shadow: 24px 0 48px -24px rgba(15, 23, 42, 0.3);
  }

  .shell-sidebar.mobile-open {
    transform: translateX(0);
  }

  .shell-topbar {
    padding: 0 12px;
    gap: 10px;
  }

  .search-scope-select {
    display: none;
  }

  .global-search-input :deep(.el-input__wrapper) {
    border-radius: 8px;
  }

  .topbar-search {
    max-width: none;
  }
}

/* 全部空间搜索结果 */
.global-result-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 420px;
  overflow-y: auto;
  min-height: 120px;
}

.global-result-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 0.15s;
}

.global-result-row:hover {
  background-color: var(--app-hover);
}

.gr-ext-badge {
  font-size: 0.65rem;
  font-weight: 700;
  color: #ffffff;
  padding: 2px 5px;
  border-radius: 4px;
  letter-spacing: 0.5px;
  min-width: 30px;
  text-align: center;
  flex-shrink: 0;
}

.gr-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.gr-name {
  font-size: 0.88rem;
  font-weight: 500;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gr-meta {
  font-size: 0.74rem;
  color: var(--app-text-faint);
}

.gr-arrow {
  color: var(--app-text-faint);
  flex-shrink: 0;
}

.global-result-row:hover .gr-arrow,
.manage-pick-item:hover .gr-arrow {
  color: var(--app-accent);
}

/* 管理入口空间选择 */
.manage-pick-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 320px;
  overflow-y: auto;
}

.manage-pick-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 0.15s;
}

.manage-pick-item:hover {
  background-color: var(--app-hover);
}

.manage-pick-name {
  flex: 1;
  min-width: 0;
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
