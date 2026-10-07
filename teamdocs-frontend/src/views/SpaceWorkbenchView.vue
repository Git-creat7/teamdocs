<template>
  <div
    class="wb-root"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- 拖拽上传遮罩 -->
    <div v-if="dragActive" class="drag-overlay" aria-hidden="true">
      <div class="drag-overlay__box">
        <el-icon :size="36"><CloudUpload /></el-icon>
        <p>松开鼠标，上传到当前文件夹</p>
      </div>
    </div>
    <!-- ===== 工作台头部：空间信息 + 操作 (对标：名称+徽章一行，描述下行，操作带文字) ===== -->
    <div class="wb-header anim-item" style="--delay: 0">
      <div class="wb-title-group">
        <div class="wb-title-row">
          <h2 class="wb-title" :title="spaceInfo?.name || ''">{{ spaceInfo?.name || '加载中…' }}</h2>
          <el-tag v-if="myRole" size="small" effect="plain" class="role-badge">
            {{ roleLabel }}
          </el-tag>
        </div>
        <span v-if="spaceInfo?.description" class="wb-desc" :title="spaceInfo.description">
          {{ spaceInfo.description }}
        </span>
      </div>

      <div class="wb-actions">
        <button type="button" class="wb-action-btn" @click="router.push({ name: 'SpaceAgent', params: { spaceId } })">
          <MessageSquare :size="16" /><span>文档问答</span>
        </button>
        <button type="button" class="wb-action-btn" @click="openMembersDrawer">
          <User :size="16" />
          <span>成员</span>
        </button>


        <input
          ref="fileInputRef"
          type="file"
          style="display: none"
          @change="handleFileSelected"
        />
        <el-button
          type="primary"
          class="upload-btn"
          :loading="uploading"
          @click="triggerUpload"
        >
          <el-icon><Upload /></el-icon>
          上 传
        </el-button>
      </div>
    </div>

    <!-- ===== 主体：文件夹树 + 文件列表 ===== -->
    <div class="wb-body" ref="wbBodyRef">
      <aside class="wb-tree-panel anim-item" v-show="!isSplitMode && !(isMobile && detailDoc)" style="--delay: 1">
        <div class="tree-panel-header">
          <span class="tree-panel-title">文件夹</span>
          <div class="tree-header-actions">
            <el-tooltip content="新建文件夹" placement="top">
              <el-icon class="tree-add-btn" @click="openCreateFolderDialog"><FolderPlus /></el-icon>
            </el-tooltip>
          </div>
        </div>
        <div class="tree-panel-content">
          <button
            type="button"
            :class="['tree-item-root', { active: currentFolderId === 0 && viewMode === 'folder' }]"
            :aria-expanded="rootTreeExpanded"
            @click="handleRootFolderClick"
          >
            <ChevronRight :size="14" :class="['tree-root-expand-icon', { expanded: rootTreeExpanded }]" />
            <el-icon class="folder-icon">
              <FolderOpen v-if="rootTreeExpanded" />
              <Folder v-else />
            </el-icon>
            <span class="folder-name">全部文件</span>
          </button>

          <!-- 受控树：不用 lazy，展开状态由 expandedKeys + node.expanded 稳住 -->
          <el-tree
            v-if="spaceId > 0"
            v-show="rootTreeExpanded"
            ref="treeRef"
            :data="treeData"
            :props="treeProps"
            node-key="id"
            highlight-current
            :expand-on-click-node="false"
            :default-expanded-keys="expandedKeys"
            :current-node-key="currentFolderId || undefined"
            class="custom-folder-tree"
            @node-click="handleTreeNodeClick"
            @node-expand="handleTreeNodeExpand"
            @node-collapse="handleTreeNodeCollapse"
          >
            <template #default="{ data }">
              <div :class="['custom-tree-node', { active: currentFolderId === data.id && viewMode === 'folder' }]">
                <el-icon class="tree-folder-icon"><Folder /></el-icon>
                <span class="node-label" :title="data.name">{{ data.name }}</span>
              </div>
            </template>
          </el-tree>
        </div>
      </aside>

      <section class="wb-content anim-item" :class="{ 'is-split': isSplitMode, 'is-mobile-detail': isMobile && detailDoc }" style="--delay: 2">

        <!-- 左侧：列表区域 (分栏时为 master，否则为全宽) -->
        <div class="master-list-area" v-show="showListInDetail">
          <div class="toolbar" :class="{'compact-mode': isSplitMode}">
          <el-button
            v-if="isSplitMode"
            text
            size="small"
            class="split-back-btn"
            @click="returnToFileTree"
          >
            <ArrowLeft :size="16" />
            返回文件树
          </el-button>
          <!-- 搜索/筛选态显示结果说明，正常态显示面包屑 -->
          <div v-if="viewMode === 'search'" class="breadcrumb-container">
            <span class="breadcrumb-current">搜索 “{{ activeKeyword }}” 的结果 ({{ docTotal }})</span>
            <el-button link size="small" class="exit-filter-btn" @click="exitFilterMode">
              返回文件列表
            </el-button>
          </div>

          <div v-else class="breadcrumb-container">
            <span
              v-for="(item, index) in breadcrumbStack"
              :key="item.id"
              class="breadcrumb-item-wrapper"
            >
              <span
                v-if="index < breadcrumbStack.length - 1"
                class="breadcrumb-link"
                @click="jumpBreadcrumb(index)"
              >
                {{ item.name }}
              </span>
              <span v-else class="breadcrumb-current">
                {{ item.name }}
              </span>
              <span v-if="index < breadcrumbStack.length - 1" class="breadcrumb-separator">/</span>
            </span>
          </div>

          <div class="toolbar-actions">


            <el-button v-show="!isSplitMode" size="small" class="new-folder-btn" @click="openCreateFolderDialog">
              <el-icon><FolderPlus /></el-icon>
              新建文件夹
            </el-button>
            <el-tooltip v-show="isSplitMode" content="新建文件夹" placement="top">
              <el-button size="small" class="new-folder-btn icon-only" @click="openCreateFolderDialog">
                <el-icon><FolderPlus /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>

        <div v-if="loadingDocuments" class="wb-loading">
          <el-skeleton :rows="6" animated />
        </div>

        <div v-else-if="combinedList.length === 0" class="wb-empty-state">
          <EmptyState
            v-if="viewMode === 'search'"
            :icon="SearchX"
            title="没有找到匹配的文档"
            description="换个关键词试试，搜索会同时匹配文档名与描述"
          >
            <el-button size="small" @click="exitFilterMode">返回文件列表</el-button>
          </EmptyState>

          <EmptyState
            v-else
            :icon="CloudUpload"
            title="这里还是空的"
            description="上传第一份文档，或新建文件夹开始整理"
          >
            <el-button type="primary" size="small" @click="triggerUpload">
              立即上传文档
            </el-button>
          </EmptyState>
        </div>

        <div v-else class="table-container stagger-rows">
          <div class="table-inner">
            <div v-if="docTotal > documents.length" class="list-cap-hint">
              当前仅显示前 {{ documents.length }} 个文档，共 {{ docTotal }} 个
            </div>
            <el-table
              :data="combinedList"
              style="width: 100%"
              :row-class-name="rowClassName"
              :cell-style="{ padding: '12px 0' }"
              :header-cell-style="{ background: 'var(--app-panel-soft)', color: 'var(--app-text-muted)', fontWeight: 600, fontSize: '0.8rem', borderBottom: '1px solid var(--app-border)' }"
            >
            <el-table-column label="名称" min-width="260">
              <template #default="{ row }">
                <button
                  v-if="row.isFolder"
                  type="button"
                  class="item-name-cell folder-cell"
                  @click="navigateToFolder(row, 'table')"
                >
                  <span class="folder-badge-icon">
                    <el-icon><Folder /></el-icon>
                  </span>
                  <span class="item-name font-medium">{{ row.name }}</span>
                </button>
                <button
                  v-else
                  type="button"
                  class="item-name-cell doc-cell"
                  @click="openDocDetail(row)"
                >
                  <FileIcon :ext="row.ext" :size="26" />
                  <span class="item-name" :title="row.name">{{ middleEllipsis(row.name, 44) }}</span>
                </button>
              </template>
            </el-table-column>


            <el-table-column v-if="!isMobile && !isSplitMode" label="大小" width="120">
              <template #default="{ row }">
                <span class="size-text">{{ row.isFolder ? '-' : formatBytes(row.fileSize) }}</span>
              </template>
            </el-table-column>

            <el-table-column v-if="!isMobile && !isSplitMode" label="更新时间" width="130">
              <template #default="{ row }">
                <span class="date-text">{{ formatRelativeTime(row.updatedAt || row.createdAt) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="" width="60" align="right">
              <template #default="{ row }">
                <el-dropdown trigger="click" @command="(cmd) => handleItemCommand(cmd, row)">
                  <span class="more-action-btn">
                    <el-icon><MoreHorizontal /></el-icon>
                  </span>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-if="!row.isFolder" command="preview">
                        <el-icon><View /></el-icon>预览
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!row.isFolder" command="download">
                        <el-icon><Download /></el-icon>下载
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!row.isFolder" command="comments">
                        <el-icon><MessageSquare /></el-icon>评论
                      </el-dropdown-item>

                      <el-dropdown-item command="rename">
                        <el-icon><Pencil /></el-icon>重命名
                      </el-dropdown-item>
                      <el-dropdown-item command="move">
                        <el-icon><FolderInput /></el-icon>移动到
                      </el-dropdown-item>
                      <el-dropdown-item command="delete" divided class="danger-item">
                        <el-icon><Trash2 /></el-icon>删除
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column>
          </el-table>
        </div>
        </div>
        </div>

        <!-- 右侧：详情面板 (分栏时为 detail，普通详情为全宽) -->
        <div class="detail-panel-wrapper" v-if="detailDoc">
          <DocumentDetailPanel
            :key="detailDoc.id"
            :space-id="spaceId"
            :doc="detailDoc"
            :my-role="myRole"
            :current-user-id="userInfo?.userId"
            :active-tab="route.query.tab || 'preview'"
            :show-back-button="!isSplitMode"
            @update:active-tab="updateDetailTab"
            @close="closeDetail"
            @download="handleDownload"
            @rename="handleDocRename"
            @move="handleItemCommand('move', $event)"
            @delete="handleDocDelete"
          />
        </div>
      </section>
    </div>

    <!-- 移动文档/文件夹的目标选择器 -->
    <el-dialog
      v-model="createFolderDialogVisible"
      title="新建文件夹"
      width="400px"
      destroy-on-close
    >
      <el-form
        ref="createFolderFormRef"
        :model="createFolderForm"
        :rules="createFolderRules"
        label-position="top"
        @submit.prevent="handleCreateFolder"
      >
        <el-form-item label="文件夹名称" prop="name">
          <el-input
            v-model.trim="createFolderForm.name"
            placeholder="请输入文件夹名称"
            maxlength="64"
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="createFolderDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="submittingFolder" @click="handleCreateFolder">
            确定
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 移动文档/文件夹的目标选择器 -->
    <FolderPickerDialog
      v-model="movePickerVisible"
      :space-id="spaceId"
      :title="moveTarget?.isFolder ? '移动文件夹到' : '移动文档到'"
      :disabled-ids="moveTarget?.isFolder ? [moveTarget.id] : []"
      @confirm="handleMoveConfirm"
    />

    <MembersDrawer
      v-model="membersVisible"
      :space-id="spaceId"
      :members="members"
      :loading="loadingMembers"
      :my-role="myRole"
      :current-user-id="userInfo?.userId"
      @refresh="loadMembers"
    />


  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Upload,
  FolderOpen,
  Folder,
  FolderPlus,
  MoreHorizontal,
  Download,
  Pencil,
  Trash2,
  User,
  MessageSquare,
  FolderInput,
  ChevronRight,
  SearchX,
  CloudUpload,
  ArrowLeft
} from 'lucide-vue-next'
import { getSpaceDetailApi, listMembersApi } from '@/api/space'
import {
  listSubFoldersApi,
  createFolderApi,
  renameFolderApi,
  deleteFolderApi,
  moveFolderApi
} from '@/api/folder'
import {
  listDocumentsApi,
  uploadDocumentApi,
  downloadDocumentApi,
  getDocumentDetailApi,
  renameDocumentApi,
  deleteDocumentApi,
  moveDocumentApi,
  searchDocumentsApi
} from '@/api/document'
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/stores'
import { useDocumentNavigation } from '@/composables/useDocumentNavigation'
import FolderPickerDialog from '@/components/FolderPickerDialog.vue'
import MembersDrawer from '@/components/MembersDrawer.vue'
import DocumentDetailPanel from '@/components/DocumentDetailPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import FileIcon from '@/components/FileIcon.vue'
import { formatBytes, formatDateTime, formatRelativeTime, getFileExt, middleEllipsis } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const { openDocument } = useDocumentNavigation()
const { userInfo } = storeToRefs(useUserStore())

// 移动端：隐藏大小/时间列，文件夹树移入列表上方横排
const mq = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mq.matches)

mq.addEventListener('change', (e) => { isMobile.value = e.matches })

const spaceId = ref(0)
const spaceInfo = ref(null)
const workspaceReadySpaceId = ref(0)
let workspaceRevision = 0
let documentDetailRequestId = 0
let folderContentRequestId = 0
let folderNavigationRequestId = 0

function isCurrentWorkspace(revision, requestedSpaceId) {
  return revision === workspaceRevision
    && route.name === 'SpaceWorkbench'
    && requestedSpaceId === Number(spaceId.value)
    && requestedSpaceId === Number(route.params.spaceId)
}

// 响应式 ResizeObserver 分栏判定
const wbBodyRef = ref(null)
const wbBodyWidth = ref(0)
let resizeObserver = null

onMounted(() => {
  resizeObserver = new ResizeObserver((entries) => {
    for (const entry of entries) {
      wbBodyWidth.value = entry.contentRect.width
    }
  })

  if (wbBodyRef.value) {
    resizeObserver.observe(wbBodyRef.value)
  }

  initFromRoute()
})

onUnmounted(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
  }
})

// 文档详情态 (文档置顶 + 评论区)
const detailDoc = ref(null)

const isSplitMode = computed(() => {
  return detailDoc.value && !isMobile.value && wbBodyWidth.value >= 900
})
const showListInDetail = computed(() => {
  if (!detailDoc.value) return true

  if (isMobile.value) return false

  if (wbBodyWidth.value < 900) return false

  return true
})

// 受控树：treeData + expandedKeys，不用 lazy
const treeRef = ref(null)
const treeData = ref([])
const expandedKeys = ref([])
const rootTreeExpanded = ref(true)
const loadedFolderIds = ref(new Set())
const treeProps = {
  label: 'name',
  children: 'children',
  isLeaf: 'leaf'
}

const currentFolderId = ref(0)
const subFolders = ref([])
const documents = ref([])
const docTotal = ref(0)
const loadingDocuments = ref(false)
const uploading = ref(false)
const fileInputRef = ref(null)

const breadcrumbStack = ref([{ id: 0, name: '根目录' }])

const viewMode = ref('folder')
const activeKeyword = ref('')


// 从最近浏览/全局搜索跳入时要高亮的文档
const highlightDocId = ref(null)

function rowClassName({ row }) {
  let cls = ''

  if (!row.isFolder && highlightDocId.value && row.id === highlightDocId.value) {
    cls += 'row-highlight '
  }

  if (!row.isFolder && detailDoc.value && row.id === detailDoc.value.id) {
    cls += 'active-row '
  }

  return cls
}

// 成员与角色
const members = ref([])
const loadingMembers = ref(false)
const myRole = ref('')

const roleLabel = computed(() => {
  const map = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }

  return map[myRole.value] || ''
})

// 弹层开关与当前操作对象
const membersVisible = ref(false)


const movePickerVisible = ref(false)
const moveTarget = ref(null)
const activeDoc = ref(null)

const createFolderDialogVisible = ref(false)
const submittingFolder = ref(false)
const createFolderFormRef = ref(null)
const createFolderForm = reactive({ name: '' })

const createFolderRules = {
  name: [
    { required: true, message: '请输入文件夹名称', trigger: 'blur' },
    { min: 1, max: 64, message: '文件夹名称最长 64 个字符', trigger: 'blur' }
  ]
}

function updateDetailTab(tab) {
  if (route.query.tab === tab) return

  // 只改 tab，保留 doc；不碰 search/highlight，避免误触发搜索刷新
  router.replace({
    query: { ...route.query, tab }
  })
}

/**
 * 返回文件树：详情 + 搜索/高亮 query 一并清掉，回到当前目录列表。
 * 详情面板「返回」按钮走这里，避免停留在搜索结果态。
 */
function closeDetail() {
  returnToFileTree()
}

/**
 * 回到目录视图的唯一出口：搜索态 / 详情态 / 高亮全部在这里清，
 * 各导航函数只调它，不再各自维护"该清哪几个"的清单。
 */
function resetViewState({ clearQuery = true } = {}) {
  detailDoc.value = null
  viewMode.value = 'folder'
  activeKeyword.value = ''
  highlightDocId.value = null

  if (
    clearQuery &&
    (route.query.search || route.query.panel || route.query.doc || route.query.highlight || route.query.t)
  ) {
    const q = { ...route.query }

    delete q.search
    delete q.panel
    delete q.doc
    delete q.tab
    delete q.highlight
    delete q.t
    router.replace({ path: route.path, query: q })
  }
}

/** 清掉 doc/tab/search/highlight/t，回到当前目录文件树 */
async function returnToFileTree() {
  resetViewState()
  await loadCurrentFolderContent()
}

function openDocDetail(doc, tab, forceWorkspace = false) {
  openDocument({
    spaceId: spaceId.value,
    documentId: doc.id,
    tab,
    forceWorkspace
  })
}

async function syncDocDetailFromId(docId) {
  if (!spaceId.value || workspaceReadySpaceId.value !== spaceId.value) return

  const requestId = ++documentDetailRequestId
  const requestedSpaceId = Number(spaceId.value)
  const revision = workspaceRevision

  const isCurrentRequest = () => (
    requestId === documentDetailRequestId
    && isCurrentWorkspace(revision, requestedSpaceId)
    && Number(route.query.doc) === docId
  )

  const existing = documents.value.find((d) => d.id === docId)

  if (existing) {
    detailDoc.value = existing
  } else if (!detailDoc.value || detailDoc.value.id !== docId) {
    detailDoc.value = { id: docId, name: '加载中...', fileSize: 0 }
  }

  try {
    const detail = await getDocumentDetailApi(requestedSpaceId, docId)

    if (
      !detail
      || Number(detail.id) !== docId
      || Number(detail.spaceId) !== requestedSpaceId
      || !isCurrentRequest()
    ) return

    const openDetail = () => {
      detailDoc.value = { ...(existing || detailDoc.value), ...detail }
    }

    // 目录态下从动态/最近文档点进来：详情立即弹出，后台把目录定位到文档所在文件夹。
    if (viewMode.value === 'folder') {
      const folderId = Number(detail.folderId ?? 0)
      const folderPath = Array.isArray(detail.folderPath) ? detail.folderPath : []

      openDetail()

      if (folderId !== Number(currentFolderId.value)) {
        await revealFolderPath(folderPath, isCurrentRequest)

        if (!isCurrentRequest()) return

        const breadcrumb = folderPath.length
          ? [{ id: 0, name: spaceInfo.value?.name || '根目录' }, ...folderPath.map((p) => ({ id: p.id, name: p.name }))]
          : null

        await navigateToFolder(
          { id: folderId, name: folderPath[folderPath.length - 1]?.name || '' },
          'force',
          { expandTree: false, keepDetail: true, breadcrumb }
        )
      }
    } else {
      openDetail()
    }
  } catch (err) {
    if (!isCurrentRequest()) return

    detailDoc.value = null

    const query = { ...route.query }

    delete query.doc
    delete query.tab
    router.replace({ query })
  }
}

const combinedList = computed(() => {
  const docsMapped = documents.value.map((d) => ({
    ...d,
    isFolder: false,
    ext: getFileExt(d.name, d.fileType)
  }))

  if (viewMode.value !== 'folder') {
    return docsMapped
  }

  const foldersMapped = subFolders.value.map((f) => ({
    ...f,
    isFolder: true,
    ext: 'FOLDER'
  }))

  return [...foldersMapped, ...docsMapped]
})

// initFromRoute() moved to onMounted

// 侧栏切换空间：同一组件实例被复用，必须 watch 路由参数整体重置
watch(() => route.params.spaceId, (val, oldVal) => {
  if (route.name !== 'SpaceWorkbench') return

  if (val && val !== oldVal) {
    initFromRoute()
  }
})

// 顶栏全局搜索：query.search 变化即触发一次空间内搜索 (t 为时间戳保证同词可重复触发)
watch(() => [route.query.search, route.query.t], () => {
  if (route.name !== 'SpaceWorkbench') return

  const keyword = String(route.query.search || '').trim()

  if (keyword && spaceId.value > 0) {
    highlightDocId.value = Number(route.query.highlight) || null
    runSearch(keyword)
  }
})

// 侧栏直达入口：query.panel=members 时打开对应面板
watch(() => [route.query.panel, route.query.t], () => {
  if (route.name !== 'SpaceWorkbench') return

  applyPanelFromQuery()
})

// 详情同步：等 spaceId 初始化后再拉，避免路由先到、space 未就绪时空请求
watch(
  () => [route.query.doc, spaceId.value],
  () => {
    if (route.name !== 'SpaceWorkbench') return

    if (!spaceId.value) return

    if (workspaceReadySpaceId.value !== spaceId.value) return

    const docId = Number(route.query.doc)

    if (docId > 0) {
      if (!detailDoc.value || detailDoc.value.id !== docId) {
        syncDocDetailFromId(docId)
      }
    } else {
      detailDoc.value = null
    }
  },
  { immediate: true }
)

function applyPanelFromQuery() {
  const panel = String(route.query.panel || '')

  if (panel === 'members') {
    membersVisible.value = true
    loadMembers()

  }

  // 侧栏「上传文档」直达：进页即唤起文件选择
  if (route.query.upload) {
    router.replace({ path: route.path })
    triggerUpload()
  }
}

// panel 和 search 一样是"query 当事件用"：消费完 (面板关闭) 即清掉，
// 否则刷新页面面板会再次弹开
function clearPanelQuery() {
  if (route.query.panel) {
    router.replace({ path: route.path })
  }
}

watch(membersVisible, (open) => {
  if (!open) clearPanelQuery()
})


async function initFromRoute() {
  const rawId = route.params.spaceId
  const spaceIdNum = Number(rawId)

  if (isNaN(spaceIdNum) || spaceIdNum <= 0) {
    ElMessage.error('无效的空间ID')
    router.replace('/home')

    return
  }

  const initRevision = ++workspaceRevision

  // 先清本地状态，再设 spaceId：避免 watcher 先开详情、随后又被 reset 清掉
  workspaceReadySpaceId.value = 0
  documentDetailRequestId++
  folderContentRequestId++
  folderNavigationRequestId++
  resetViewState({ clearQuery: false })
  currentFolderId.value = 0
  treeData.value = []
  expandedKeys.value = []
  rootTreeExpanded.value = true
  loadedFolderIds.value = new Set()
  subFolders.value = []
  documents.value = []
  docTotal.value = 0
  members.value = []
  myRole.value = ''

  spaceId.value = spaceIdNum

  const ok = await loadSpaceDetail(spaceIdNum, initRevision)

  if (!ok || !isCurrentWorkspace(initRevision, spaceIdNum)) return

  const tasks = [
    loadRootTree(initRevision),
    loadMembers(),
  ]

  // 带搜索参数进来 (全局搜索跳转) 直接进搜索态；有 doc 时优先目录+详情，不进搜索
  const keyword = String(route.query.search || '').trim()

  if (keyword && !route.query.doc) {
    highlightDocId.value = Number(route.query.highlight) || null
    tasks.push(runSearch(keyword))
  } else {
    tasks.push(loadCurrentFolderContent())
  }

  await Promise.all(tasks)

  if (!isCurrentWorkspace(initRevision, spaceIdNum)) return

  workspaceReadySpaceId.value = spaceIdNum

  applyPanelFromQuery()

  const docId = Number(route.query.doc)

  if (docId > 0) {
    await syncDocDetailFromId(docId)
  }
}

async function loadSpaceDetail(requestedSpaceId = Number(spaceId.value), revision = workspaceRevision) {
  try {
    const data = await getSpaceDetailApi(requestedSpaceId)

    if (!isCurrentWorkspace(revision, requestedSpaceId)) return false

    if (data) {
      spaceInfo.value = data
      breadcrumbStack.value = [{ id: 0, name: data.name || '根目录' }]

      return true
    }

    router.replace('/home')

    return false
  } catch (err) {
    if (isCurrentWorkspace(revision, requestedSpaceId)) {
      router.replace('/home')
    }

    return false
  }
}

async function loadMembers() {
  loadingMembers.value = true

  try {
    const list = await listMembersApi(spaceId.value)

    members.value = list
    resolveMyRole()
  } catch (err) {
    members.value = []
  } finally {
    loadingMembers.value = false
  }
}

function resolveMyRole() {
  const uid = userInfo.value?.userId

  if (!uid || members.value.length === 0) return

  const me = members.value.find((m) => m.userId === uid)

  myRole.value = me?.role || ''
}

watch(userInfo, resolveMyRole)


function openMembersDrawer() {
  membersVisible.value = true
  loadMembers()
}

/* ========== 文件夹树 ========== */

function mapFolderNodes(list) {
  return (list || []).map((f) => ({
    id: f.id,
    name: f.name,
    parentId: f.parentId ?? 0,
    children: [],
    leaf: false
  }))
}

function findTreeNode(nodes, id) {
  for (const node of nodes) {
    if (node.id === id) return node

    if (node.children?.length) {
      const found = findTreeNode(node.children, id)

      if (found) return found
    }
  }

  return null
}

function collectAncestorIds(folderId) {
  const ids = []
  let node = findTreeNode(treeData.value, folderId)

  while (node && node.parentId) {
    ids.unshift(node.parentId)
    node = findTreeNode(treeData.value, node.parentId)
  }

  return ids
}

function applyExpandedState(keys) {
  nextTick(() => {
    if (!treeRef.value) return

    keys.forEach((id) => {
      const node = treeRef.value.getNode(id)

      if (node && !node.expanded) {
        node.expanded = true
      }
    })
  })
}

function ensureExpanded(folderId) {
  if (!folderId) return

  const next = new Set(expandedKeys.value)

  collectAncestorIds(folderId).forEach((id) => next.add(id))
  next.add(folderId)
  expandedKeys.value = Array.from(next)
  applyExpandedState(expandedKeys.value)
}

async function fetchFolderChildren(parentId, requestedSpaceId = Number(spaceId.value)) {
  try {
    const list = await listSubFoldersApi(requestedSpaceId, parentId)

    return mapFolderNodes(list)
  } catch (err) {
    return []
  }
}

async function loadRootTree(revision = workspaceRevision) {
  const requestedSpaceId = Number(spaceId.value)
  const children = await fetchFolderChildren(0, requestedSpaceId)

  if (!isCurrentWorkspace(revision, requestedSpaceId)) return

  treeData.value = children
  loadedFolderIds.value = new Set([0])
}

async function revealFolderPath(folderPath, isCurrentRequest) {
  if (!folderPath.length) return

  let parentId = 0

  for (const item of folderPath) {
    if (!isCurrentRequest()) return

    if (!findTreeNode(treeData.value, item.id)) {
      await loadTreeChildren(parentId, true, isCurrentRequest)
    }

    parentId = item.id
  }

  if (!isCurrentRequest()) return

  const ancestorIds = folderPath.slice(0, -1).map((item) => item.id)

  expandedKeys.value = Array.from(new Set([...expandedKeys.value, ...ancestorIds]))
  applyExpandedState(expandedKeys.value)
}

async function loadTreeChildren(parentId, force = false, isCurrentRequest = null) {
  if (isCurrentRequest && !isCurrentRequest()) return

  if (!force && loadedFolderIds.value.has(parentId)) return

  const requestedSpaceId = Number(spaceId.value)
  const revision = workspaceRevision
  const children = await fetchFolderChildren(parentId, requestedSpaceId)

  if (
    !isCurrentWorkspace(revision, requestedSpaceId)
    || (isCurrentRequest && !isCurrentRequest())
  ) return

  if (parentId === 0) {
    // 根层：尽量保留已展开节点的已加载子树
    if (!force && treeData.value.length > 0) {
      const oldMap = new Map(treeData.value.map((n) => [n.id, n]))

      treeData.value = children.map((n) => {
        const old = oldMap.get(n.id)

        if (old && loadedFolderIds.value.has(n.id)) {
          return { ...n, children: old.children, leaf: old.leaf }
        }

        return n
      })
    } else {
      treeData.value = children
    }
  } else {
    const parent = findTreeNode(treeData.value, parentId)

    if (parent) {
      parent.children = children
      parent.leaf = children.length === 0
    }
  }

  // 强刷根层会重建整棵树，旧子树的“已加载”标记也必须一起失效。
  const nextLoaded = parentId === 0 && force
    ? new Set()
    : new Set(loadedFolderIds.value)
  nextLoaded.add(parentId)
  loadedFolderIds.value = nextLoaded
}

async function refreshTreeFolder(parentFolderId = 0) {
  await loadTreeChildren(parentFolderId, true)

  if (parentFolderId) ensureExpanded(parentFolderId)

  applyExpandedState(expandedKeys.value)
}

function buildBreadcrumbFromTree(folderId, fallbackName = '') {
  const root = { id: 0, name: spaceInfo.value?.name || '根目录' }

  if (!folderId) return [root]

  const path = []
  let node = findTreeNode(treeData.value, folderId)

  while (node) {
    path.unshift({ id: node.id, name: node.name })

    if (!node.parentId) break

    node = findTreeNode(treeData.value, node.parentId)
  }

  if (path.length > 0 && path[path.length - 1].id === folderId) {
    return [root, ...path]
  }

  const existIndex = breadcrumbStack.value.findIndex((item) => item.id === folderId)

  if (existIndex !== -1) {
    return breadcrumbStack.value.slice(0, existIndex + 1)
  }

  return [root, { id: folderId, name: fallbackName || '未命名文件夹' }]
}


async function runSearch(keyword) {
  // 已在看详情时不要被搜索把页面重置成结果列表（例如切评论 tab 不应触发刷新）
  if (route.query.doc) return

  detailDoc.value = null
  loadingDocuments.value = true
  viewMode.value = 'search'
  activeKeyword.value = keyword

  try {
    const page = await searchDocumentsApi(spaceId.value, keyword, 1, 100)

    documents.value = page.records
    docTotal.value = page.total

    // 全局搜索带 highlight 跳入且命中结果：直接打开详情，省一次点击
    if (highlightDocId.value) {
      const hit = page.records.find((d) => d.id === highlightDocId.value)

      if (hit && Number(route.query.doc) !== hit.id) {
        openDocDetail(hit, undefined, true)
      }
    }
  } catch (err) {
    documents.value = []
    docTotal.value = 0
  } finally {
    loadingDocuments.value = false
  }
}


// 打标/摘标只本地增量修补映射，不再整体重建 (省一轮全量反查)


async function exitFilterMode() {
  if (viewMode.value === 'folder' && !detailDoc.value) return

  await returnToFileTree()
}

/* ========== 目录内容 ========== */

async function loadCurrentFolderContent() {
  const requestId = ++folderContentRequestId
  const requestedSpaceId = Number(spaceId.value)
  const requestedFolderId = Number(currentFolderId.value)
  const revision = workspaceRevision

  const isCurrentRequest = () => (
    requestId === folderContentRequestId
    && isCurrentWorkspace(revision, requestedSpaceId)
    && requestedFolderId === Number(currentFolderId.value)
  )

  loadingDocuments.value = true

  try {
    const folderList = await listSubFoldersApi(requestedSpaceId, requestedFolderId)

    if (!isCurrentRequest()) return

    subFolders.value = folderList

    // 把当前目录的子文件夹同步进树
    const mapped = mapFolderNodes(folderList)

    if (requestedFolderId === 0) {
      const oldMap = new Map(treeData.value.map((n) => [n.id, n]))

      treeData.value = mapped.map((n) => {
        const old = oldMap.get(n.id)

        if (old && loadedFolderIds.value.has(n.id)) {
          return { ...n, children: old.children, leaf: old.leaf }
        }

        return n
      })
    } else {
      const parent = findTreeNode(treeData.value, requestedFolderId)

      if (parent) {
        const oldChildMap = new Map((parent.children || []).map((n) => [n.id, n]))

        parent.children = mapped.map((n) => {
          const old = oldChildMap.get(n.id)

          if (old && loadedFolderIds.value.has(n.id)) {
            return { ...n, children: old.children, leaf: old.leaf }
          }

          return n
        })
        parent.leaf = parent.children.length === 0
      }
    }

    const nextLoaded = new Set(loadedFolderIds.value)
    nextLoaded.add(requestedFolderId)
    loadedFolderIds.value = nextLoaded

    const docPage = await listDocumentsApi(requestedSpaceId, requestedFolderId, 1, 100)

    if (!isCurrentRequest()) return

    documents.value = docPage.records
    docTotal.value = docPage.total
  } catch (err) {
    if (!isCurrentRequest()) return

    subFolders.value = []
    documents.value = []
    docTotal.value = 0
  } finally {
    if (isCurrentRequest()) {
      loadingDocuments.value = false
      applyExpandedState(expandedKeys.value)
    }
  }
}

function syncTreeSelection(folderId) {
  nextTick(() => {
    if (!treeRef.value) return

    treeRef.value.setCurrentKey(folderId || null)
  })
}

async function navigateToFolder(folder, fromSource = 'table', { expandTree = true, keepDetail = false, breadcrumb = null } = {}) {
  if (!folder) return

  const requestId = ++folderNavigationRequestId
  const requestedSpaceId = Number(spaceId.value)
  const requestedFolderId = Number(folder.id)
  const revision = workspaceRevision

  const isCurrentRequest = () => (
    requestId === folderNavigationRequestId
    && isCurrentWorkspace(revision, requestedSpaceId)
  )

  const isCurrentFolder = () => (
    isCurrentRequest() && requestedFolderId === Number(currentFolderId.value)
  )

  // 处于筛选态或详情态时，任何目录导航先统一回到目录视图并强制刷新；
  // keepDetail 时保留详情面板与 query（如从动态定位目录），只清视图状态
  if (viewMode.value !== 'folder' || detailDoc.value) {
    if (keepDetail) {
      viewMode.value = 'folder'
      activeKeyword.value = ''
      highlightDocId.value = null
    } else {
      resetViewState()
    }

    fromSource = 'force'
  }

  if (folder.id === currentFolderId.value && fromSource !== 'force') {
    if (folder.id) {
      await loadTreeChildren(folder.id)

      if (!isCurrentFolder()) return

      if (expandTree) ensureExpanded(folder.id)
    }

    syncTreeSelection(folder.id || 0)

    return
  }

  if (folder.id === 0) {
    currentFolderId.value = 0
    breadcrumbStack.value = [{ id: 0, name: spaceInfo.value?.name || '根目录' }]
    await loadCurrentFolderContent()

    if (!isCurrentFolder()) return

    syncTreeSelection(0)

    return
  }

  await loadTreeChildren(folder.id)

  if (!isCurrentRequest()) return

  const existIndex = breadcrumbStack.value.findIndex((item) => item.id === folder.id)

  if (breadcrumb) {
    breadcrumbStack.value = breadcrumb
  } else if (existIndex !== -1) {
    breadcrumbStack.value = breadcrumbStack.value.slice(0, existIndex + 1)
  } else if (fromSource === 'table') {
    breadcrumbStack.value.push({ id: folder.id, name: folder.name })
  } else {
    breadcrumbStack.value = buildBreadcrumbFromTree(folder.id, folder.name)
  }

  currentFolderId.value = folder.id

  if (expandTree) ensureExpanded(folder.id)

  await loadCurrentFolderContent()

  if (!isCurrentFolder()) return

  syncTreeSelection(folder.id)
}

async function handleRootFolderClick() {
  rootTreeExpanded.value = !rootTreeExpanded.value

  if (!rootTreeExpanded.value) collapseAllTreeNodes()

  await navigateToFolder({ id: 0, name: spaceInfo.value?.name || '根目录' }, 'sidebar')
}

async function handleTreeNodeClick(data, node) {
  if (!data?.id) return

  const interactionRequestId = ++folderNavigationRequestId
  const requestedSpaceId = Number(spaceId.value)
  const revision = workspaceRevision

  const isCurrentInteraction = () => (
    interactionRequestId === folderNavigationRequestId
    && isCurrentWorkspace(revision, requestedSpaceId)
  )

  if (node?.expanded) {
    node.collapse()
    handleTreeNodeCollapse(data)
  } else {
    await loadTreeChildren(data.id)

    if (!isCurrentInteraction()) return

    if (!treeRef.value?.getNode(data.id)?.isLeaf) {
      ensureExpanded(data.id)
    }
  }

  if (!isCurrentInteraction()) return

  await navigateToFolder(data, 'sidebar', { expandTree: false })
}

async function handleTreeNodeExpand(data) {
  if (!data?.id) return

  if (!expandedKeys.value.includes(data.id)) {
    expandedKeys.value = [...expandedKeys.value, data.id]
  }

  await loadTreeChildren(data.id)
  applyExpandedState(expandedKeys.value)
}

function collectDescendantIds(node) {
  const ids = []
  const stack = [...(node?.children || [])]

  while (stack.length) {
    const cur = stack.pop()

    ids.push(cur.id)

    if (cur.children?.length) stack.push(...cur.children)
  }

  return ids
}

function handleTreeNodeCollapse(data) {
  if (!data?.id) return

  // 收起父级时连同全部子孙一起收起，避免"只能一级一级收回"
  const node = findTreeNode(treeData.value, data.id)
  const removeIds = new Set([data.id, ...collectDescendantIds(node)])

  expandedKeys.value = expandedKeys.value.filter((id) => !removeIds.has(id))
  nextTick(() => {
    if (!treeRef.value) return

    removeIds.forEach((id) => {
      const n = treeRef.value.getNode(id)

      if (n?.expanded) n.collapse()
    })
  })
}

function collapseAllTreeNodes() {
  const ids = [...expandedKeys.value]

  expandedKeys.value = []
  nextTick(() => {
    if (!treeRef.value) return

    ids.forEach((id) => {
      const node = treeRef.value.getNode(id)

      if (node?.expanded) node.collapse()
    })
  })
}

async function jumpBreadcrumb(index) {
  if (index < 0 || index >= breadcrumbStack.value.length) return

  const breadcrumb = breadcrumbStack.value.slice(0, index + 1)
  const target = breadcrumb[index]

  await navigateToFolder(target, 'force', { breadcrumb })
}

/* ========== 上传 / 下载 / 重命名 / 移动 / 删除 ========== */

function triggerUpload() {
  if (fileInputRef.value) {
    fileInputRef.value.click()
  }
}

// 与后端 multipart 单文件上限 (100MB) 对齐的前端预检
const MAX_UPLOAD_SIZE = 100 * 1024 * 1024

// ===== 拖拽上传：整个工作台可接收文件，上传到当前文件夹 =====
const dragActive = ref(false)
let dragDepth = 0

function hasFiles(event) {
  return Array.from(event.dataTransfer?.types || []).includes('Files')
}

function onDragEnter(event) {
  if (!hasFiles(event)) return

  dragDepth += 1
  dragActive.value = true
}

function onDragLeave(event) {
  if (!hasFiles(event)) return

  dragDepth = Math.max(0, dragDepth - 1)

  if (dragDepth === 0) dragActive.value = false
}

function onDrop(event) {
  dragDepth = 0
  dragActive.value = false

  const files = Array.from(event.dataTransfer?.files || [])

  if (files.length > 0) uploadFiles(files)
}

async function uploadFiles(files) {
  const oversized = files.filter((f) => f.size > MAX_UPLOAD_SIZE)
  const valid = files.filter((f) => f.size <= MAX_UPLOAD_SIZE)

  if (oversized.length > 0) {
    ElMessage.error(`${oversized.length} 个文件超过 100MB 上限，已跳过`)
  }

  if (valid.length === 0) return

  uploading.value = true

  let okCount = 0

  try {
    for (const file of valid) {
      try {
        await uploadDocumentApi(spaceId.value, currentFolderId.value, file)
        okCount += 1
      } catch (err) {
        // 拦截器已提示单个文件失败
      }
    }
  } finally {
    uploading.value = false
  }

  if (okCount > 0) {
    ElMessage.success(`上传成功 ${okCount} 个文档`)

    if (viewMode.value === 'folder') {
      await loadCurrentFolderContent()
    }
  }
}

async function handleFileSelected(event) {
  const file = event.target.files?.[0]

  if (!file) return

  if (file.size > MAX_UPLOAD_SIZE) {
    ElMessage.error('文件大小不能超过 100MB')

    if (event?.target) event.target.value = ''

    return
  }

  uploading.value = true

  try {
    await uploadDocumentApi(spaceId.value, currentFolderId.value, file)
    ElMessage.success('文档上传成功')

    if (viewMode.value === 'folder') {
      await loadCurrentFolderContent()
    }
  } catch (err) {
    // 拦截器处理
  } finally {
    uploading.value = false

    if (event?.target) {
      event.target.value = ''
    }
  }
}

function handleItemCommand(command, row) {
  if (row.isFolder) {
    if (command === 'rename') {
      handleFolderRename(row)
    } else if (command === 'move') {
      moveTarget.value = row
      movePickerVisible.value = true
    } else if (command === 'delete') {
      handleFolderDelete(row)
    }
  } else if (command === 'preview') {
    openDocDetail(row, 'preview')
  } else if (command === 'download') {
    handleDownload(row)
  } else if (command === 'comments') {
    openDocDetail(row, 'comments')
  } else if (command === 'rename') {
    handleDocRename(row)
  } else if (command === 'move') {
    moveTarget.value = row
    movePickerVisible.value = true
  } else if (command === 'delete') {
    handleDocDelete(row)
  }
}

async function handleMoveConfirm(targetFolderId) {
  const target = moveTarget.value

  if (!target) return

  try {
    if (target.isFolder) {
      if (targetFolderId === target.id) {
        ElMessage.warning('不能移动到自己下面')

        return
      }

      await moveFolderApi(spaceId.value, target.id, targetFolderId)
      ElMessage.success(`文件夹 "${target.name}" 已移动`)
      await Promise.all([loadCurrentFolderContent(), refreshTreeFolder(0)])
    } else {
      await moveDocumentApi(spaceId.value, target.id, targetFolderId)
      ElMessage.success(`文档 "${target.name}" 已移动`)

      if (detailDoc.value && detailDoc.value.id === target.id) {
        closeDetail()
      }

      await loadCurrentFolderContent()
    }
  } catch (err) {
    // 拦截器处理
  } finally {
    moveTarget.value = null
  }
}

function handleFolderRename(folder) {
  ElMessageBox.prompt('请输入新文件夹名称', '重命名文件夹', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: folder.name,
    inputValidator: (val) => {
      if (!val || !val.trim()) return '文件夹名称不能为空'

      if (val.trim().length > 64) return '名称不能超过64个字符'

      return true
    }
  }).then(async ({ value }) => {
    try {
      const newName = value.trim()

      await renameFolderApi(spaceId.value, folder.id, newName)
      ElMessage.success('文件夹重命名成功')

      breadcrumbStack.value = breadcrumbStack.value.map((item) =>
        item.id === folder.id ? { ...item, name: newName } : item
      )

      const treeNode = findTreeNode(treeData.value, folder.id)

      if (treeNode) {
        treeNode.name = newName
      }

      await loadCurrentFolderContent()
      await refreshTreeFolder(folder.parentId ?? currentFolderId.value)
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}

function handleFolderDelete(folder) {
  ElMessageBox.confirm(
    `删除文件夹 "${folder.name}" 会连同其子文件夹一起删除，其中的文档进入回收站。确定删除吗？`,
    '删除文件夹',
    {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(async () => {
    try {
      await deleteFolderApi(spaceId.value, folder.id)
      ElMessage.success('文件夹已删除')

      // 若当前正位于被删文件夹内，退回根目录
      if (currentFolderId.value === folder.id) {
        currentFolderId.value = 0
        breadcrumbStack.value = [{ id: 0, name: spaceInfo.value?.name || '根目录' }]
      }

      await Promise.all([loadCurrentFolderContent(), refreshTreeFolder(0)])
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}

async function handleDownload(doc) {
  try {
    const url = await downloadDocumentApi(spaceId.value, doc.id)

    if (!url) return

    const a = document.createElement('a')

    a.href = url
    a.target = '_blank'
    a.rel = 'noopener noreferrer'
    document.body.appendChild(a)
    a.click()
    a.remove()
  } catch (err) {
    // 拦截器处理
  }
}

function splitFileName(fullName) {
  if (!fullName) return { baseName: '', ext: '' }

  const lastDotIdx = fullName.lastIndexOf('.')

  if (lastDotIdx <= 0 || lastDotIdx === fullName.length - 1) {
    return { baseName: fullName, ext: '' }
  }

  return {
    baseName: fullName.substring(0, lastDotIdx),
    ext: fullName.substring(lastDotIdx)
  }
}

function handleDocRename(doc) {
  const { baseName, ext } = splitFileName(doc.name)

  ElMessageBox.prompt(
    ext ? `原文件扩展名: ${ext} (重命名将自动保留类型)` : '请输入新文档名称',
    '重命名文档',
    {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: baseName,
      inputValidator: (val) => {
        if (!val || !val.trim()) return '文档名称不能为空'

        const inputStr = val.trim()
        const inputSplit = splitFileName(inputStr)

        if (ext && inputSplit.ext && inputSplit.ext.toLowerCase() !== ext.toLowerCase()) {
          return `请勿修改扩展名（原类型为 ${ext}）`
        }

        return true
      }
    }
  ).then(async ({ value }) => {
    const inputStr = value.trim()
    let finalName = inputStr

    if (ext) {
      const inputSplit = splitFileName(inputStr)

      if (inputSplit.ext.toLowerCase() === ext.toLowerCase()) {
        finalName = inputStr
      } else {
        finalName = inputStr + ext
      }
    }

    try {
      await renameDocumentApi(spaceId.value, doc.id, finalName)
      ElMessage.success('文档重命名成功')

      if (detailDoc.value && detailDoc.value.id === doc.id) {
        detailDoc.value.name = finalName
      }

      if (viewMode.value === 'folder') {
        await loadCurrentFolderContent()
      } else {
        const found = documents.value.find(d => d.id === doc.id)

        if (found) found.name = finalName
      }
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}

function handleDocDelete(doc) {
  ElMessageBox.confirm(`确定要删除文档 "${doc.name}" 吗？删除后可在回收站找回。`, '删除确认', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteDocumentApi(spaceId.value, doc.id)
      ElMessage.success('文档已删除，可在回收站找回')
      window.dispatchEvent(new CustomEvent('teamdocs:recent-docs-changed'))

      if (detailDoc.value && detailDoc.value.id === doc.id) {
        closeDetail()
      }

      if (viewMode.value === 'folder') {
        await loadCurrentFolderContent()
      } else {
        documents.value = documents.value.filter((d) => d.id !== doc.id)
        docTotal.value = Math.max(0, docTotal.value - 1)
      }
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}

function openCreateFolderDialog() {
  createFolderForm.name = ''
  createFolderDialogVisible.value = true
}

async function handleCreateFolder() {
  if (!createFolderFormRef.value || submittingFolder.value) return

  submittingFolder.value = true

  try {
    await createFolderFormRef.value.validate()

    await createFolderApi(spaceId.value, {
      name: createFolderForm.name.trim(),
      parentId: currentFolderId.value
    })
    ElMessage.success('文件夹创建成功')
    createFolderDialogVisible.value = false
    await loadCurrentFolderContent()
    await refreshTreeFolder(currentFolderId.value)
  } catch (err) {
    // 拦截器处理
  } finally {
    submittingFolder.value = false
  }
}

function formatDate(dateStr) {
  return formatDateTime(dateStr)
}
</script>

<style scoped>
.wb-root {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

/* 拖拽上传遮罩 */
.drag-overlay {
  position: absolute;
  inset: 0;
  z-index: 40;
  background: rgba(37, 99, 235, 0.06);
  border: 2px dashed var(--app-accent);
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.drag-overlay__box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 28px 44px;
  border-radius: 14px;
  background: var(--app-panel);
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.18);
  color: var(--app-accent);
  font-weight: 600;
}

.drag-overlay__box p {
  margin: 0;
  font-size: 0.95rem;
  color: var(--app-text);
}

/* ============ 分栏布局 (Master-Detail) ============ */
.master-list-area {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  height: 100%;
}

.wb-content.is-split {
  flex-direction: row;
}

.wb-content.is-split .master-list-area {
  flex: none;
  width: clamp(300px, 35%, 380px);
  border-right: 1px solid var(--app-border);
}

.detail-panel-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 560px;
  height: 100%;
}

.wb-content.is-mobile-detail .detail-panel-wrapper {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: var(--app-bg);
  min-width: 0;
}

.wb-content.is-mobile-detail .master-list-area {
  display: none;
}

/* ============ 顶栏操作区 ============ */
.toolbar {
  padding: 0.8rem 1.2rem;
  border-bottom: 1px solid var(--app-border-soft);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: var(--app-panel-soft);
  min-width: 0;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 紧凑态工具栏 (分栏时) */
.toolbar.compact-mode .breadcrumb-container {
  display: none;
}

.toolbar.compact-mode .toolbar-actions {
  width: auto;
  margin-left: auto;
  justify-content: flex-end;
}

.split-back-btn {
  flex-shrink: 0;
  color: var(--app-text-muted);
}

.split-back-btn:hover {
  color: var(--app-accent);
}

.new-folder-btn.icon-only {
  padding: 8px;
}

/* ===== 工作台头部 ===== */
.wb-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 24px 14px;
  background: var(--app-panel);
  border-bottom: 1px solid var(--app-border);
  flex-shrink: 0;
  min-width: 0;
}

.wb-title-group {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
  overflow: hidden;
}

.wb-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.wb-title {
  font-size: 1.15rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}

.role-badge {
  flex-shrink: 0;
}

.wb-desc {
  font-size: 0.8rem;
  color: var(--app-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.wb-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

/* 图标+文字动作按钮 (对标图) */
.wb-action-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--app-text-2);
  font-size: 0.85rem;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease-standard),
              color var(--dur-fast) var(--ease-standard);
}

.wb-action-btn:hover {
  background: var(--app-hover);
  color: var(--app-accent);
}

.upload-btn {
  margin-left: 6px;
  border-radius: 6px;
  font-weight: 500;
}

/* ===== 主体 ===== */
.wb-body {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

.wb-tree-panel {
  width: 224px;
  background: var(--app-panel);
  border-right: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  min-height: 0;
}

.tree-panel-header {
  padding: 14px 14px 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tree-header-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.tree-panel-title {
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--app-text-faint);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.tree-add-btn {
  font-size: 15px;
  color: var(--app-text-faint);
  cursor: pointer;
  padding: 3px;
  border-radius: 4px;
}

.tree-add-btn:hover {
  color: var(--app-accent);
  background: var(--app-accent-weak);
}

.tree-panel-content {
  flex: 1;
  min-height: 0;
  padding: 4px 8px 8px;
  overflow-x: hidden;
  overflow-y: auto;
}

.tree-item-root {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  font-family: inherit;
  font-size: 0.86rem;
  text-align: left;
  color: var(--app-text-2);
  cursor: pointer;
  transition: all 0.15s ease;
  margin-bottom: 2px;
}

.tree-item-root:hover {
  background-color: var(--app-hover);
}

.tree-item-root:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: -2px;
}

.tree-item-root.active {
  background-color: var(--app-accent-weak);
  color: var(--app-accent);
  font-weight: 600;
}

.active-row {
  background-color: var(--app-hover-soft) !important;
}

.folder-icon {
  font-size: 1rem;
  color: var(--app-text-muted);
}

.tree-root-expand-icon {
  flex-shrink: 0;
  color: var(--app-text-faint);
  transition: transform 150ms var(--ease-standard), color var(--dur-fast) var(--ease-standard);
}

.tree-root-expand-icon.expanded {
  color: var(--app-text-muted);
  transform: rotate(90deg);
}

.tree-item-root.active .folder-icon {
  color: var(--app-accent);
}

.danger-item {
  color: var(--el-color-danger);
}

.doc-preview-dialog__header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-right: 24px;
}

.doc-preview-dialog__title {
  flex: 1 1 auto;
  min-width: 0;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

:deep(.doc-preview-dialog .el-dialog__body) {
  height: 78vh;
  padding-top: 8px;
}

.folder-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.custom-folder-tree {
  --el-tree-node-hover-bg-color: #f1f5f9;
  background: transparent;
  color: var(--app-text-2);
}

.custom-folder-tree :deep(.el-tree-node__content) {
  height: 36px;
  border-radius: 8px;
  padding-right: 8px;
  transition: background-color 0.15s ease;
}

.custom-folder-tree :deep(.el-tree-node__expand-icon) {
  font-size: 12px;
  color: var(--app-text-faint);
  padding: 4px;
  margin-right: 2px;
  box-sizing: content-box;
  transition: transform 150ms var(--ease-standard), color var(--dur-fast) var(--ease-standard);
}

.custom-folder-tree :deep(.el-tree-node__expand-icon:hover) {
  color: var(--app-text-2);
}

.custom-folder-tree :deep(.el-tree-node__expand-icon.expanded) {
  color: var(--app-text-muted);
}

.custom-folder-tree :deep(.el-tree-node__expand-icon.is-leaf) {
  color: transparent;
  cursor: default;
}

.custom-folder-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background-color: var(--app-accent-weak);
}

.custom-tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.86rem;
  width: 100%;
  min-width: 0;
  overflow: hidden;
}

.custom-tree-node.active {
  color: var(--app-accent);
  font-weight: 600;
}

.tree-folder-icon {
  font-size: 0.95rem;
  color: var(--app-text-muted);
  flex-shrink: 0;
}

.custom-tree-node.active .tree-folder-icon {
  color: var(--app-accent);
}

.node-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

/* ===== 内容区 ===== */
.wb-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--app-bg);
  margin: 14px;
  border-radius: 12px;
  border: 1px solid var(--app-border);
  overflow: hidden;
}

/* 内容区工具栏 (wb-content 内) */
.wb-content .toolbar {
  padding: 0.9rem 1.5rem;
  border-bottom: 1px solid var(--app-border-soft);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: var(--app-panel);
  min-width: 0;
}

.breadcrumb-container {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.875rem;
  min-width: 0;
  overflow: hidden;
  flex: 1;
}

.breadcrumb-item-wrapper {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.breadcrumb-link {
  color: var(--app-text-muted);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.breadcrumb-link:hover {
  color: var(--app-accent);
  text-decoration: underline;
}

.breadcrumb-current {
  color: var(--app-text);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}

.breadcrumb-separator {
  color: var(--app-text-faint);
  flex-shrink: 0;
}

.exit-filter-btn {
  margin-left: 10px;
  flex-shrink: 0;
}

.toolbar-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.new-folder-btn {
  border-radius: 6px;
}

.wb-loading {
  padding: 2rem;
}

.wb-empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
}

.table-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 12px 16px 16px;
}

/* 表格内容居中限宽，两侧留白，带卡片感 */
.table-inner {
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
  display: flex;
  flex-direction: column;
  flex: 1;
  background: var(--app-panel);
  border-radius: 12px;
  border: 1px solid var(--app-border-soft);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04), 0 1px 2px rgba(0, 0, 0, 0.03);
  overflow: hidden;
}

/* 分栏模式下表格占满窄列，不居中 */
.wb-content.is-split .table-inner {
  max-width: none;
  margin: 0;
}

.list-cap-hint {
  padding: 0.75rem 1.25rem;
  font-size: 0.8rem;
  color: var(--app-text-faint);
  background: var(--app-panel-soft);
  border-bottom: 1px solid var(--app-border-soft);
}

.item-name-cell {
  appearance: none;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.item-name-cell:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.doc-cell {
  transition: transform 0.15s ease;
}

.doc-cell:hover .item-name {
  color: var(--app-accent);
}

.doc-cell:hover {
  transform: translateX(2px);
}

.folder-badge-icon {
  width: 28px;
  height: 28px;
  background: linear-gradient(135deg, var(--app-hover) 0%, var(--app-panel-soft) 100%);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--app-text-2);
  border: 1px solid var(--app-border-soft);
  transition: all 0.2s ease;
}

.folder-cell:hover .folder-badge-icon {
  background: var(--app-accent-weak);
  color: var(--app-accent);
  border-color: var(--app-accent);
}

.ext-badge {
  font-size: 0.65rem;
  font-weight: 700;
  color: #ffffff;
  padding: 3px 6px;
  border-radius: 6px;
  letter-spacing: 0.5px;
  min-width: 30px;
  text-align: center;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}

.item-name {
  font-size: 0.875rem;
  color: var(--app-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.15s ease;
}

.item-name.font-medium {
  font-weight: 500;
}

.type-text, .size-text, .date-text {
  font-size: 0.825rem;
  color: var(--app-text-muted);
  font-variant-numeric: tabular-nums;
}

.more-action-btn {
  cursor: pointer;
  color: var(--app-text-faint);
  padding: 6px 10px;
  border-radius: 6px;
  transition: all 0.2s;
  outline: none;
  border: 1px solid transparent;
}

.more-action-btn:hover {
  color: var(--app-text);
  background-color: var(--app-hover);
  border-color: var(--app-border);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.danger-item {
  color: #ef4444;
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  /* 头部纵向堆叠，描述隐藏 */
  .wb-header {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
    padding: 12px 14px 10px;
  }

  .wb-title { max-width: none; }

  .wb-desc { display: none; }

  .wb-actions { justify-content: flex-end; }

  /* 主体纵向：文件夹树变横向滚动条带，列表占满 */
  .wb-body {
    flex-direction: column;
  }

  .wb-tree-panel {
    width: 100%;
    max-height: 176px;
    border-right: none;
    border-bottom: 1px solid var(--app-border);
  }

  .wb-content {
    margin: 10px;
  }

  /* 工具栏换行，筛选控件占满一行 */
  .toolbar {
    flex-wrap: wrap;
    padding: 0.6rem 0.9rem;
  }

  .toolbar-actions {
    width: 100%;
    justify-content: space-between;
  }

.breadcrumb-current { max-width: 180px; }

.table-container :deep(.el-table) { max-width: none !important; }
}

/* 从最近浏览/全局搜索定位过来的目标行高亮 */
.table-container :deep(.row-highlight) {
  background-color: #fef9e7 !important;
  animation: highlight-pulse 2s ease 1;
}

.table-container :deep(.row-highlight:hover > td) {
  background-color: #fdf3d0 !important;
}

@keyframes highlight-pulse {
  0% { background-color: #fde68a; }

  100% { background-color: #fef9e7; }
}
</style>
