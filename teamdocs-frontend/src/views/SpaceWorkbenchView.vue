<template>
  <div class="wb-root">
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
        <button type="button" class="wb-action-btn" @click="openMembersDrawer">
          <User :size="16" />
          <span>成员</span>
        </button>
        <button type="button" class="wb-action-btn" @click="tagManagerVisible = true">
          <Tag :size="16" />
          <span>标签管理</span>
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
    <div class="wb-body">
      <aside class="wb-tree-panel anim-item" style="--delay: 1">
        <div class="tree-panel-header">
          <span class="tree-panel-title">文件夹</span>
          <div class="tree-header-actions">
            <el-tooltip content="收起全部" placement="top">
              <el-icon class="tree-add-btn" @click="collapseAllFolders"><ChevronsDownUp /></el-icon>
            </el-tooltip>
            <el-tooltip content="新建文件夹" placement="top">
              <el-icon class="tree-add-btn" @click="openCreateFolderDialog"><FolderPlus /></el-icon>
            </el-tooltip>
          </div>
        </div>
        <div class="tree-panel-content">
          <div
            :class="['tree-item-root', { active: currentFolderId === 0 && viewMode === 'folder' }]"
            @click="navigateToFolder({ id: 0, name: spaceInfo?.name || '根目录' }, 'sidebar')"
          >
            <el-icon class="folder-icon"><FolderOpen /></el-icon>
            <span class="folder-name">全部文件</span>
          </div>

          <!-- 受控树：不用 lazy，展开状态由 expandedKeys + node.expanded 稳住 -->
          <el-tree
            v-if="spaceId > 0"
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

      <section class="wb-content anim-item" style="--delay: 2">
        <!-- 文档详情态：文档卡置顶 + 评论区占满 -->
        <DocumentDetailPanel
          v-if="detailDoc"
          :space-id="spaceId"
          :doc="detailDoc"
          :tags="docTagsMap[detailDoc.id] || []"
          :my-role="myRole"
          :current-user-id="userInfo?.userId"
          @close="detailDoc = null"
          @download="handleDownload"
        />

        <template v-else>
        <div class="toolbar">
          <!-- 搜索/筛选态显示结果说明，正常态显示面包屑 -->
          <div v-if="viewMode === 'search'" class="breadcrumb-container">
            <span class="breadcrumb-current">搜索 “{{ activeKeyword }}” 的结果 ({{ docTotal }})</span>
            <el-button link size="small" class="exit-filter-btn" @click="exitFilterMode">
              返回文件列表
            </el-button>
          </div>
          <div v-else-if="viewMode === 'tag'" class="breadcrumb-container">
            <span class="breadcrumb-current">标签 “{{ activeTagName }}” 下的文档 ({{ docTotal }})</span>
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
            <el-select
              v-model="selectedTagId"
              placeholder="按标签筛选"
              clearable
              filterable
              size="small"
              class="tag-filter-select"
              @change="handleTagFilterChange"
            >
              <el-option
                v-for="tag in spaceTags"
                :key="tag.id"
                :label="tag.name"
                :value="tag.id"
              />
            </el-select>

            <el-button size="small" class="new-folder-btn" @click="openCreateFolderDialog">
              <el-icon><FolderPlus /></el-icon>
              新建文件夹
            </el-button>
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
            description="换个关键词试试，搜索会同时匹配文档名与标签"
          >
            <el-button size="small" @click="exitFilterMode">返回文件列表</el-button>
          </EmptyState>
          <EmptyState
            v-else-if="viewMode === 'tag'"
            :icon="Tag"
            title="该标签下还没有文档"
            description="在文档的更多菜单里可以为它打上这个标签"
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
          <div v-if="docTotal > documents.length" class="list-cap-hint">
            当前仅显示前 {{ documents.length }} 个文档，共 {{ docTotal }} 个
          </div>
          <el-table
            :data="combinedList"
            style="width: 100%; max-width: 1080px"
            :row-class-name="rowClassName"
          >
            <el-table-column label="名称" min-width="260">
              <template #default="{ row }">
                <div
                  v-if="row.isFolder"
                  class="item-name-cell folder-cell"
                  @click="navigateToFolder(row, 'table')"
                >
                  <div class="folder-badge-icon">
                    <el-icon><Folder /></el-icon>
                  </div>
                  <span class="item-name font-medium">{{ row.name }}</span>
                </div>
                <div v-else class="item-name-cell doc-cell" @click="openDocDetail(row)">
                  <FileIcon :ext="row.ext" :size="26" />
                  <span class="item-name" :title="row.name">{{ middleEllipsis(row.name, 44) }}</span>
                </div>
              </template>
            </el-table-column>

            <!-- 独立标签列 (对标图) -->
            <el-table-column v-if="!isMobile" label="标签" width="200">
              <template #default="{ row }">
                <div v-if="!row.isFolder && (docTagsMap[row.id] || []).length" class="tag-cell">
                  <span
                    v-for="tagName in docTagsMap[row.id].slice(0, 2)"
                    :key="tagName"
                    class="row-tag-chip"
                    :style="tagStyle(tagName)"
                  >
                    {{ tagName }}
                  </span>
                  <span v-if="docTagsMap[row.id].length > 2" class="row-tag-more">
                    +{{ docTagsMap[row.id].length - 2 }}
                  </span>
                </div>
                <span v-else class="type-text">{{ row.isFolder ? '-' : '' }}</span>
              </template>
            </el-table-column>

            <el-table-column v-if="!isMobile" label="大小" width="120">
              <template #default="{ row }">
                <span class="size-text">{{ row.isFolder ? '-' : formatBytes(row.fileSize) }}</span>
              </template>
            </el-table-column>

            <el-table-column v-if="!isMobile" label="更新时间" width="130">
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
                      <el-dropdown-item v-if="!row.isFolder" command="download">
                        <el-icon><Download /></el-icon>下载
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!row.isFolder" command="comments">
                        <el-icon><MessageSquare /></el-icon>评论
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!row.isFolder" command="tags">
                        <el-icon><Tag /></el-icon>标签
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
        </template>
      </section>
    </div>

    <!-- 新建文件夹 -->
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
        @keyup.enter="handleCreateFolder"
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

    <!-- 空间成员抽屉 -->
    <MembersDrawer
      v-model="membersVisible"
      :space-id="spaceId"
      :members="members"
      :loading="loadingMembers"
      :my-role="myRole"
      :current-user-id="userInfo?.userId"
      @refresh="loadMembers"
    />

    <!-- 标签管理 -->
    <TagManagerDialog
      v-model="tagManagerVisible"
      :space-id="spaceId"
      :my-role="myRole"
      @changed="handleTagsChangedGlobally"
    />

    <!-- 文档标签编辑 (标签数据复用 docTagsMap，弹窗自身零请求) -->
    <DocumentTagsPopover
      v-model="docTagsVisible"
      :space-id="spaceId"
      :document-id="activeDoc?.id"
      :document-name="activeDoc?.name || ''"
      :all-tags="spaceTags"
      :attached-tag-names="docTagsMap[activeDoc?.id] || []"
      @changed="handleDocTagsChanged"
    />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
  Tag,
  MessageSquare,
  FolderInput,
  ChevronsDownUp,
  SearchX,
  CloudUpload
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
import { listTagsApi, listDocumentsByTagApi } from '@/api/tag'
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/stores'
import { useDocTags } from '@/composables/useDocTags'
import { tagStyle } from '@/utils/tagColors'
import FolderPickerDialog from '@/components/FolderPickerDialog.vue'
import MembersDrawer from '@/components/MembersDrawer.vue'
import TagManagerDialog from '@/components/TagManagerDialog.vue'
import DocumentTagsPopover from '@/components/DocumentTagsPopover.vue'
import DocumentDetailPanel from '@/components/DocumentDetailPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import FileIcon from '@/components/FileIcon.vue'
import { formatBytes, formatDateTime, formatRelativeTime, getFileExt, middleEllipsis } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const { userInfo } = storeToRefs(useUserStore())

// 移动端：隐藏大小/时间列，文件夹树移入列表上方横排
const mq = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mq.matches)
mq.addEventListener('change', (e) => { isMobile.value = e.matches })

const spaceId = ref(0)
const spaceInfo = ref(null)

// 受控树：treeData + expandedKeys，不用 lazy
const treeRef = ref(null)
const treeData = ref([])
const expandedKeys = ref([])
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

// 视图模式：folder(浏览目录) / search(搜索结果) / tag(标签筛选)
const viewMode = ref('folder')
const activeKeyword = ref('')
const selectedTagId = ref(null)
const activeTagName = ref('')
const spaceTags = ref([])

// 从最近浏览/全局搜索跳入时要高亮的文档
const highlightDocId = ref(null)

function rowClassName({ row }) {
  if (!row.isFolder && highlightDocId.value && row.id === highlightDocId.value) {
    return 'row-highlight'
  }
  return ''
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
const tagManagerVisible = ref(false)
const docTagsVisible = ref(false)
const movePickerVisible = ref(false)
const moveTarget = ref(null)
const activeDoc = ref(null)

// 文档详情态 (文档置顶 + 评论区)
const detailDoc = ref(null)

/**
 * 回到目录视图的唯一出口：搜索态 / 标签筛选态 / 详情态 / 高亮全部在这里清，
 * 各导航函数只调它，不再各自维护"该清哪几个"的清单。
 */
function resetViewState({ clearQuery = true } = {}) {
  detailDoc.value = null
  viewMode.value = 'folder'
  activeKeyword.value = ''
  selectedTagId.value = null
  activeTagName.value = ''
  highlightDocId.value = null
  if (clearQuery && (route.query.search || route.query.panel)) {
    router.replace({ path: route.path })
  }
}

function openDocDetail(doc) {
  // 先用列表行数据即时渲染，再用详情接口刷新 (含最新标签；后端同时记入最近浏览)
  detailDoc.value = doc
  getDocumentDetailApi(spaceId.value, doc.id)
    .then((detail) => {
      if (!detail || detailDoc.value?.id !== doc.id) return
      detailDoc.value = { ...doc, ...detail }
      setFromDetail(doc.id, detail.tags)
    })
    .catch(() => {
      // 详情拉取失败降级保持列表行数据
    })
}

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

const combinedList = computed(() => {
  const docsMapped = documents.value.map((d) => ({
    ...d,
    isFolder: false,
    ext: getFileExt(d.name, d.fileType)
  }))
  // 搜索/标签筛选结果是全空间范围的纯文档列表，不掺当前目录的文件夹
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

onMounted(() => {
  initFromRoute()
})

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

// 侧栏直达入口：query.panel=members/tags 时打开对应面板
watch(() => [route.query.panel, route.query.t], () => {
  if (route.name !== 'SpaceWorkbench') return
  applyPanelFromQuery()
})

function applyPanelFromQuery() {
  const panel = String(route.query.panel || '')
  if (panel === 'members') {
    membersVisible.value = true
    loadMembers()
  } else if (panel === 'tags') {
    tagManagerVisible.value = true
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

watch(tagManagerVisible, (open) => {
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

  spaceId.value = spaceIdNum

  // 重置视图状态 (query 保留：search/panel 参数在下面消费)
  resetViewState({ clearQuery: false })
  currentFolderId.value = 0
  treeData.value = []
  expandedKeys.value = []
  loadedFolderIds.value = new Set()
  subFolders.value = []
  documents.value = []
  docTotal.value = 0
  resetDocTags()
  members.value = []
  myRole.value = ''

  const ok = await loadSpaceDetail()
  if (!ok) return

  const tasks = [loadRootTree(), loadMembers(), loadSpaceTags()]

  // 带搜索参数进来 (全局搜索跳转) 直接进搜索态，否则加载根目录
  const keyword = String(route.query.search || '').trim()
  if (keyword) {
    highlightDocId.value = Number(route.query.highlight) || null
    tasks.push(runSearch(keyword))
  } else {
    tasks.push(loadCurrentFolderContent())
  }
  await Promise.all(tasks)

  // 侧栏直达面板 (成员/标签)
  applyPanelFromQuery()
}

async function loadSpaceDetail() {
  try {
    const data = await getSpaceDetailApi(spaceId.value)
    if (data) {
      spaceInfo.value = data
      breadcrumbStack.value = [{ id: 0, name: data.name || '根目录' }]
      return true
    }
    router.replace('/home')
    return false
  } catch (err) {
    router.replace('/home')
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

// 文档标签缓存：拉取/修补/失效规则全部封装在 useDocTags
const {
  docTagsMap,
  loadForDocs: loadTagsForDocs,
  applyToggle: applyTagToggle,
  setFromDetail,
  invalidateAll: invalidateDocTags,
  reset: resetDocTags
} = useDocTags(spaceId)

async function loadSpaceTags() {
  try {
    spaceTags.value = await listTagsApi(spaceId.value)
  } catch (err) {
    spaceTags.value = []
  }
}

// 标签管理里重命名/删除标签后：标签名全变了，缓存整体失效并对当前列表重拉
async function handleTagsChangedGlobally() {
  await loadSpaceTags()
  invalidateDocTags()
  loadTagsForDocs(documents.value)
}

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

async function fetchFolderChildren(parentId) {
  try {
    const list = await listSubFoldersApi(spaceId.value, parentId)
    return mapFolderNodes(list)
  } catch (err) {
    return []
  }
}

async function loadRootTree() {
  const children = await fetchFolderChildren(0)
  treeData.value = children
  loadedFolderIds.value = new Set([0])
}

async function loadTreeChildren(parentId, force = false) {
  if (!force && loadedFolderIds.value.has(parentId)) return

  const children = await fetchFolderChildren(parentId)
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

  const nextLoaded = new Set(loadedFolderIds.value)
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

/* ========== 搜索 / 标签筛选 ========== */

async function runSearch(keyword) {
  detailDoc.value = null
  loadingDocuments.value = true
  viewMode.value = 'search'
  activeKeyword.value = keyword
  selectedTagId.value = null
  try {
    const page = await searchDocumentsApi(spaceId.value, keyword, 1, 100)
    documents.value = page.records
    docTotal.value = page.total
    loadTagsForDocs(documents.value)

    // 从最近浏览/全局搜索带 highlight 跳入且命中结果：直接打开详情，省一次点击
    if (highlightDocId.value) {
      const hit = page.records.find((d) => d.id === highlightDocId.value)
      if (hit) openDocDetail(hit)
    }
  } catch (err) {
    documents.value = []
    docTotal.value = 0
  } finally {
    loadingDocuments.value = false
  }
}

async function handleTagFilterChange(tagId) {
  if (!tagId) {
    exitFilterMode()
    return
  }
  loadingDocuments.value = true
  viewMode.value = 'tag'
  activeTagName.value = spaceTags.value.find((t) => t.id === tagId)?.name || ''
  activeKeyword.value = ''
  try {
    const page = await listDocumentsByTagApi(spaceId.value, tagId, 1, 100)
    documents.value = page.records
    docTotal.value = page.total
    loadTagsForDocs(documents.value)
  } catch (err) {
    documents.value = []
    docTotal.value = 0
  } finally {
    loadingDocuments.value = false
  }
}

// 打标/摘标只本地增量修补映射，不再整体重建 (省一轮全量反查)
function handleDocTagsChanged({ docId, tag, added }) {
  applyTagToggle(docId, tag.name, added)

  // 标签筛选视图下摘掉当前筛选标签：直接本地移除该行
  if (viewMode.value === 'tag' && selectedTagId.value === tag.id && !added) {
    documents.value = documents.value.filter((d) => d.id !== docId)
    docTotal.value = Math.max(0, docTotal.value - 1)
  }
}

async function exitFilterMode() {
  if (viewMode.value === 'folder' && !detailDoc.value) return
  resetViewState()
  await loadCurrentFolderContent()
}

/* ========== 目录内容 ========== */

async function loadCurrentFolderContent() {
  loadingDocuments.value = true
  try {
    const folderList = await listSubFoldersApi(spaceId.value, currentFolderId.value)
    subFolders.value = folderList

    // 把当前目录的子文件夹同步进树
    const mapped = mapFolderNodes(folderList)
    if (currentFolderId.value === 0) {
      const oldMap = new Map(treeData.value.map((n) => [n.id, n]))
      treeData.value = mapped.map((n) => {
        const old = oldMap.get(n.id)
        if (old && loadedFolderIds.value.has(n.id)) {
          return { ...n, children: old.children, leaf: old.leaf }
        }
        return n
      })
    } else {
      const parent = findTreeNode(treeData.value, currentFolderId.value)
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
    nextLoaded.add(currentFolderId.value)
    loadedFolderIds.value = nextLoaded

    const docPage = await listDocumentsApi(spaceId.value, currentFolderId.value, 1, 100)
    documents.value = docPage.records
    docTotal.value = docPage.total
    loadTagsForDocs(documents.value)
  } catch (err) {
    subFolders.value = []
    documents.value = []
    docTotal.value = 0
  } finally {
    loadingDocuments.value = false
    applyExpandedState(expandedKeys.value)
  }
}

function syncTreeSelection(folderId) {
  nextTick(() => {
    if (!treeRef.value) return
    treeRef.value.setCurrentKey(folderId || null)
  })
}

async function navigateToFolder(folder, fromSource = 'table') {
  if (!folder) return

  // 处于筛选态或详情态时，任何目录导航先统一回到目录视图并强制刷新
  if (viewMode.value !== 'folder' || detailDoc.value) {
    resetViewState()
    fromSource = 'force'
  }

  if (folder.id === currentFolderId.value && fromSource !== 'force') {
    if (folder.id) {
      await loadTreeChildren(folder.id)
      ensureExpanded(folder.id)
    }
    syncTreeSelection(folder.id || 0)
    return
  }

  if (folder.id === 0) {
    currentFolderId.value = 0
    breadcrumbStack.value = [{ id: 0, name: spaceInfo.value?.name || '根目录' }]
    await loadCurrentFolderContent()
    syncTreeSelection(0)
    return
  }

  const existIndex = breadcrumbStack.value.findIndex((item) => item.id === folder.id)
  if (existIndex !== -1) {
    breadcrumbStack.value = breadcrumbStack.value.slice(0, existIndex + 1)
  } else if (fromSource === 'table') {
    breadcrumbStack.value.push({ id: folder.id, name: folder.name })
  } else {
    breadcrumbStack.value = buildBreadcrumbFromTree(folder.id, folder.name)
  }

  currentFolderId.value = folder.id
  await loadTreeChildren(folder.id)
  ensureExpanded(folder.id)
  await loadCurrentFolderContent()
  syncTreeSelection(folder.id)
}

async function handleTreeNodeClick(data) {
  if (!data?.id) return
  // 先拉子节点写入 treeData，再标记展开，避免 lazy 闪缩
  await loadTreeChildren(data.id)
  ensureExpanded(data.id)
  await navigateToFolder(data, 'sidebar')
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
      if (n && n.expanded) n.expanded = false
    })
  })
}

function collapseAllFolders() {
  const ids = [...expandedKeys.value]
  expandedKeys.value = []
  nextTick(() => {
    if (!treeRef.value) return
    ids.forEach((id) => {
      const n = treeRef.value.getNode(id)
      if (n && n.expanded) n.expanded = false
    })
  })
}

async function jumpBreadcrumb(index) {
  if (index < 0 || index >= breadcrumbStack.value.length) return
  breadcrumbStack.value = breadcrumbStack.value.slice(0, index + 1)
  const target = breadcrumbStack.value[index]
  currentFolderId.value = target.id
  if (target.id) {
    await loadTreeChildren(target.id)
    ensureExpanded(target.id)
  }
  await loadCurrentFolderContent()
  syncTreeSelection(target.id || 0)
}

/* ========== 上传 / 下载 / 重命名 / 移动 / 删除 ========== */

function triggerUpload() {
  if (fileInputRef.value) {
    fileInputRef.value.click()
  }
}

// 与后端 multipart 容器上限 (20MB) 对齐的前端预检
const MAX_UPLOAD_SIZE = 20 * 1024 * 1024

async function handleFileSelected(event) {
  const file = event.target.files?.[0]
  if (!file) return

  if (file.size > MAX_UPLOAD_SIZE) {
    ElMessage.error('文件大小不能超过 20MB')
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
  } else if (command === 'download') {
    handleDownload(row)
  } else if (command === 'comments') {
    openDocDetail(row)
  } else if (command === 'tags') {
    activeDoc.value = row
    docTagsVisible.value = true
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
      if (viewMode.value === 'folder') {
        await loadCurrentFolderContent()
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
      if (viewMode.value === 'folder') {
        await loadCurrentFolderContent()
      } else {
        documents.value = documents.value.filter((d) => d.id !== doc.id)
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
  if (!createFolderFormRef.value) return
  try {
    await createFolderFormRef.value.validate()
  } catch (err) {
    return
  }

  submittingFolder.value = true
  try {
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
}

/* ===== 工作台头部 ===== */
.wb-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 24px 12px;
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
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 0.86rem;
  color: var(--app-text-2);
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease-standard);
  margin-bottom: 4px;
}

.tree-item-root:hover {
  background-color: var(--app-hover);
}

.tree-item-root.active {
  background-color: var(--app-accent-weak);
  color: var(--app-accent);
  font-weight: 600;
}

.folder-icon {
  font-size: 1rem;
  color: var(--app-text-muted);
}

.tree-item-root.active .folder-icon {
  color: var(--app-accent);
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
  height: 34px;
  border-radius: 6px;
  padding-right: 8px;
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
  background: var(--app-panel);
  margin: 14px;
  border-radius: 10px;
  border: 1px solid var(--app-border);
  overflow: hidden;
}

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

.tag-filter-select {
  width: 130px;
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
}

.list-cap-hint {
  padding: 0.5rem 1.25rem 0;
  font-size: 0.8rem;
  color: var(--app-text-faint);
}

.item-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.folder-cell {
  cursor: pointer;
}

.doc-cell {
  cursor: pointer;
}

.doc-cell:hover .item-name {
  color: var(--app-accent);
}

.folder-badge-icon {
  width: 26px;
  height: 26px;
  background: var(--app-hover);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--app-text-2);
}

.ext-badge {
  font-size: 0.65rem;
  font-weight: 700;
  color: #ffffff;
  padding: 2px 5px;
  border-radius: 4px;
  letter-spacing: 0.5px;
  min-width: 28px;
  text-align: center;
}

.item-name {
  font-size: 0.875rem;
  color: var(--app-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-name.font-medium {
  font-weight: 500;
}

.tag-cell {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  overflow: hidden;
}

/* 行内标签胶囊 */
.row-tag-chip {
  font-size: 0.68rem;
  font-weight: 500;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid transparent;
  white-space: nowrap;
  flex-shrink: 0;
}

.row-tag-more {
  font-size: 0.68rem;
  color: var(--app-text-faint);
  flex-shrink: 0;
}

.type-text, .size-text, .date-text {
  font-size: 0.825rem;
  color: var(--app-text-muted);
}

.more-action-btn {
  cursor: pointer;
  color: var(--app-text-faint);
  padding: 4px 8px;
  border-radius: 4px;
  transition: all 0.2s;
  outline: none;
}

.more-action-btn:hover {
  color: var(--app-text);
  background-color: var(--app-hover);
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

  .tag-filter-select { flex: 1; }

  .breadcrumb-current { max-width: 180px; }

  /* 表格行内标签 chips 收起，名称列吃满 */
  .row-tag-chip,
  .row-tag-more { display: none; }

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
