<template>
  <div class="workspace-container">
    <!-- 顶栏：返回我的空间 + 空间名称(副标题描述) + 上传主按钮 + 用户区 -->
    <header class="workspace-navbar">
      <div class="nav-left">
        <el-button type="info" plain size="small" class="back-btn" @click="goBack">
          ← 我的空间
        </el-button>
        <div class="nav-divider"></div>
        <div class="space-title-group">
          <h2 class="space-title" :title="spaceInfo?.name || ''">{{ spaceInfo?.name || '加载中...' }}</h2>
          <el-tooltip
            v-if="spaceInfo?.description"
            placement="bottom-start"
            :show-after="180"
            :hide-after="0"
            popper-class="space-desc-popper"
          >
            <template #content>
              <div class="desc-pop-card">
                <div class="desc-pop-label">空间描述</div>
                <div class="desc-pop-body">{{ spaceInfo.description }}</div>
              </div>
            </template>
            <span class="space-subtitle">
              {{ spaceInfo.description }}
            </span>
          </el-tooltip>
        </div>
      </div>

      <div class="nav-right">
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
          <el-icon class="btn-icon"><Upload /></el-icon>
          上 传
        </el-button>

        <div class="nav-divider"></div>

        <span class="username">{{ userInfo?.username || '团队成员' }}</span>
        <el-button type="info" plain size="small" @click="handleLogout">
          退出登录
        </el-button>
      </div>
    </header>

    <main class="workspace-body">
      <aside class="sidebar-container">
        <div class="sidebar-header">
          <span class="sidebar-title">文件夹</span>
        </div>
        <div class="sidebar-content">
          <div
            :class="['tree-item-root', { active: currentFolderId === 0 }]"
            @click="navigateToFolder({ id: 0, name: spaceInfo?.name || '根目录' }, 'sidebar')"
          >
            <el-icon class="folder-icon"><FolderOpened /></el-icon>
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
              <div :class="['custom-tree-node', { active: currentFolderId === data.id }]">
                <el-icon class="tree-folder-icon"><Folder /></el-icon>
                <span class="node-label" :title="data.name">{{ data.name }}</span>
              </div>
            </template>
          </el-tree>
        </div>
      </aside>

      <section class="content-area">
        <div class="toolbar">
          <div class="breadcrumb-container">
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
            <el-button size="small" class="new-folder-btn" @click="openCreateFolderDialog">
              <el-icon><FolderAdd /></el-icon>
              新建文件夹
            </el-button>
          </div>
        </div>

        <div v-if="loadingDocuments" class="workspace-loading">
          <el-skeleton :rows="6" animated />
        </div>

        <div
          v-else-if="subFolders.length === 0 && documents.length === 0"
          class="workspace-empty-state"
        >
          <el-empty
            description="还没有文件，上传后会出现在这里"
            :image-size="110"
          >
            <el-button type="primary" size="small" @click="triggerUpload">
              立即上传文档
            </el-button>
          </el-empty>
        </div>

        <div v-else class="table-container">
          <div v-if="docTotal > documents.length" class="list-cap-hint">
            当前仅显示前 {{ documents.length }} 个文档，共 {{ docTotal }} 个
          </div>
          <el-table :data="combinedList" style="width: 100%" hover>
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
                <div v-else class="item-name-cell">
                  <div
                    class="ext-badge"
                    :style="{ backgroundColor: getFileTypeColor(row.ext) }"
                  >
                    {{ row.ext }}
                  </div>
                  <span class="item-name" :title="row.name">{{ row.name }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <span class="type-text">{{ row.isFolder ? '文件夹' : row.ext }}</span>
              </template>
            </el-table-column>

            <el-table-column label="大小" width="140">
              <template #default="{ row }">
                <span class="size-text">{{ row.isFolder ? '-' : formatBytes(row.fileSize) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="更新时间" width="180">
              <template #default="{ row }">
                <span class="date-text">{{ formatDate(row.updatedAt || row.createdAt) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="" width="60" align="right">
              <template #default="{ row }">
                <el-dropdown trigger="click" @command="(cmd) => handleItemCommand(cmd, row)">
                  <span class="more-action-btn">
                    <el-icon><MoreFilled /></el-icon>
                  </span>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-if="!row.isFolder" command="download">
                        <el-icon><Download /></el-icon>下载
                      </el-dropdown-item>
                      <el-dropdown-item command="rename">
                        <el-icon><Edit /></el-icon>重命名
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!row.isFolder" command="delete" divided class="danger-item">
                        <el-icon><Delete /></el-icon>删除
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>
    </main>

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
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Upload,
  FolderOpened,
  Folder,
  FolderAdd,
  MoreFilled,
  Download,
  Edit,
  Delete
} from '@element-plus/icons-vue'
import { getUserInfoApi, logoutApi } from '@/api/user'
import { getSpaceDetailApi } from '@/api/space'
import { listSubFoldersApi, createFolderApi, renameFolderApi } from '@/api/folder'
import {
  listDocumentsApi,
  uploadDocumentApi,
  downloadDocumentApi,
  renameDocumentApi,
  deleteDocumentApi
} from '@/api/document'

const route = useRoute()
const router = useRouter()

const spaceId = ref(0)
const loading = ref(true)
const spaceInfo = ref(null)
const userInfo = ref(null)

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
  const foldersMapped = subFolders.value.map((f) => ({
    ...f,
    isFolder: true,
    ext: 'FOLDER'
  }))
  const docsMapped = documents.value.map((d) => ({
    ...d,
    isFolder: false,
    ext: getFileExt(d.name, d.fileType)
  }))
  return [...foldersMapped, ...docsMapped]
})

onMounted(async () => {
  const rawId = route.params.spaceId
  const spaceIdNum = Number(rawId)

  if (isNaN(spaceIdNum) || spaceIdNum <= 0) {
    ElMessage.error('无效的空间ID')
    router.replace('/spaces')
    return
  }

  spaceId.value = spaceIdNum
  fetchUserInfo()

  const ok = await loadSpaceDetail()
  if (!ok) return
  await Promise.all([loadRootTree(), loadCurrentFolderContent()])
})

async function fetchUserInfo() {
  try {
    const data = await getUserInfoApi()
    userInfo.value = data
  } catch (e) {
    // 忽略
  }
}

async function loadSpaceDetail() {
  try {
    loading.value = true
    const data = await getSpaceDetailApi(spaceId.value)
    if (data) {
      spaceInfo.value = data
      breadcrumbStack.value = [{ id: 0, name: data.name || '根目录' }]
      return true
    }
    router.replace('/spaces')
    return false
  } catch (err) {
    router.replace('/spaces')
    return false
  } finally {
    loading.value = false
  }
}

function mapFolderNodes(list) {
  return (Array.isArray(list) ? list : []).map((f) => ({
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

async function loadCurrentFolderContent() {
  loadingDocuments.value = true
  try {
    const folderList = await listSubFoldersApi(spaceId.value, currentFolderId.value)
    subFolders.value = Array.isArray(folderList) ? folderList : []

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

    const docRes = await listDocumentsApi(spaceId.value, currentFolderId.value, 1, 100)
    if (docRes && Array.isArray(docRes.records)) {
      documents.value = docRes.records
      docTotal.value = Number(docRes.total) || docRes.records.length
    } else if (Array.isArray(docRes)) {
      documents.value = docRes
      docTotal.value = docRes.length
    } else {
      documents.value = []
      docTotal.value = 0
    }
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

function handleTreeNodeExpand(data) {
  if (!data?.id) return
  if (!expandedKeys.value.includes(data.id)) {
    expandedKeys.value = [...expandedKeys.value, data.id]
  }
  loadTreeChildren(data.id)
}

function handleTreeNodeCollapse(data) {
  if (!data?.id) return
  expandedKeys.value = expandedKeys.value.filter((id) => id !== data.id)
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

function triggerUpload() {
  if (fileInputRef.value) {
    fileInputRef.value.click()
  }
}

async function handleFileSelected(event) {
  const file = event.target.files?.[0]
  if (!file) return

  uploading.value = true
  try {
    await uploadDocumentApi(spaceId.value, currentFolderId.value, file)
    ElMessage.success('文档上传成功')
    await loadCurrentFolderContent()
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
    }
  } else if (command === 'download') {
    handleDownload(row)
  } else if (command === 'rename') {
    handleDocRename(row)
  } else if (command === 'delete') {
    handleDocDelete(row)
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
      await loadCurrentFolderContent()
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}

function handleDocDelete(doc) {
  ElMessageBox.confirm(`确定要删除文档 "${doc.name}" 吗？`, '删除确认', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteDocumentApi(spaceId.value, doc.id)
      ElMessage.success('文档已删除')
      await loadCurrentFolderContent()
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

function goBack() {
  router.push('/spaces')
}

async function handleLogout() {
  try {
    await logoutApi()
    ElMessage.success('已安全退出登录')
  } catch (err) {
    // 忽略
  } finally {
    localStorage.removeItem('teamdocs_token')
    router.replace('/login')
  }
}

function getFileExt(filename, fileType) {
  if (filename && filename.includes('.')) {
    return filename.split('.').pop().toUpperCase()
  }
  if (fileType) {
    return fileType.toUpperCase()
  }
  return 'FILE'
}

function getFileTypeColor(ext) {
  const map = {
    PDF: '#ef4444',
    DOCX: '#2563eb',
    DOC: '#2563eb',
    XLSX: '#16a34a',
    XLS: '#16a34a',
    PNG: '#d97706',
    JPG: '#d97706',
    JPEG: '#d97706',
    ZIP: '#8b5cf6',
    RAR: '#8b5cf6',
    MP4: '#ec4899'
  }
  return map[ext] || '#64748b'
}

function formatBytes(bytes) {
  if (bytes === 0 || !bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return '-'
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hours = String(d.getHours()).padStart(2, '0')
  const minutes = String(d.getMinutes()).padStart(2, '0')
  return `${year}/${month}/${day} ${hours}:${minutes}`
}
</script>

<style scoped>
.workspace-container {
  height: 100vh;
  background-color: #f8fafc;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.workspace-navbar {
  height: 56px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 1.5rem;
  flex-shrink: 0;
  overflow: hidden;
  box-sizing: border-box;
  width: 100%;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1 1 0%;
  overflow: hidden;
}

.back-btn {
  font-size: 0.85rem;
  flex-shrink: 0;
}

.nav-divider {
  width: 1px;
  height: 16px;
  background-color: #cbd5e1;
  flex-shrink: 0;
}

/* 标题/描述纵向叠放：长文案只截断左侧，绝不挤右侧按钮 */
.space-title-group {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 1px;
  min-width: 0;
  flex: 1 1 0%;
  overflow: hidden;
}

.space-title {
  font-size: 1.05rem;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
  line-height: 1.25;
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.space-subtitle {
  display: block;
  font-size: 0.75rem;
  color: #64748b;
  line-height: 1.25;
  max-width: 28em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: default;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 0 0 auto;
  white-space: nowrap;
}

.upload-btn {
  background-color: #2563eb;
  border-color: #2563eb;
  border-radius: 6px;
  font-weight: 500;
  padding: 8px 16px;
}

.upload-btn:hover {
  background-color: #1d4ed8;
  border-color: #1d4ed8;
}

.username {
  font-size: 0.875rem;
  color: #475569;
  font-weight: 500;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-body {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

.sidebar-container {
  width: 240px;
  background: #ffffff;
  border-right: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  min-height: 0;
}

.sidebar-header {
  padding: 1.25rem 1.25rem 0.5rem 1.25rem;
}

.sidebar-title {
  font-size: 0.775rem;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.sidebar-content {
  flex: 1;
  min-height: 0;
  padding: 0.5rem;
  overflow-x: hidden;
  overflow-y: auto;
}

.tree-item-root {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 0.875rem;
  color: #334155;
  cursor: pointer;
  transition: background-color 0.2s;
  margin-bottom: 4px;
}

.tree-item-root:hover {
  background-color: #f1f5f9;
}

.tree-item-root.active {
  background-color: #eff6ff;
  color: #2563eb;
  font-weight: 600;
}

.folder-icon {
  font-size: 1rem;
  color: #64748b;
}

.tree-item-root.active .folder-icon {
  color: #2563eb;
}

.folder-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.custom-folder-tree {
  --el-tree-node-hover-bg-color: #f1f5f9;
  background: transparent;
  color: #334155;
}

.custom-folder-tree :deep(.el-tree-node__content) {
  height: 34px;
  border-radius: 6px;
  padding-right: 8px;
}

.custom-folder-tree :deep(.el-tree-node__expand-icon) {
  font-size: 12px;
  color: #94a3b8;
  padding: 4px;
  margin-right: 2px;
  box-sizing: content-box;
  transition: transform 0.15s ease, color 0.15s ease;
}

.custom-folder-tree :deep(.el-tree-node__expand-icon:hover) {
  color: #475569;
}

.custom-folder-tree :deep(.el-tree-node__expand-icon.expanded) {
  color: #64748b;
}

.custom-folder-tree :deep(.el-tree-node__expand-icon.is-leaf) {
  color: transparent;
  cursor: default;
}

.custom-folder-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background-color: #eff6ff;
}

.custom-tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.875rem;
  width: 100%;
  min-width: 0;
  overflow: hidden;
}

.custom-tree-node.active {
  color: #2563eb;
  font-weight: 600;
}

.tree-folder-icon {
  font-size: 0.95rem;
  color: #64748b;
  flex-shrink: 0;
}

.custom-tree-node.active .tree-folder-icon {
  color: #2563eb;
}

.node-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.content-area {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  margin: 1.25rem;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  overflow: hidden;
}

.toolbar {
  padding: 0.85rem 1.25rem;
  border-bottom: 1px solid #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fafafa;
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
  color: #64748b;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.breadcrumb-link:hover {
  color: #2563eb;
  text-decoration: underline;
}

.breadcrumb-current {
  color: #0f172a;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}

.breadcrumb-separator {
  color: #cbd5e1;
  flex-shrink: 0;
}

.toolbar-actions {
  flex-shrink: 0;
}

.new-folder-btn {
  border-radius: 6px;
}

.workspace-loading {
  padding: 2rem;
}

.workspace-empty-state {
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
  color: #94a3b8;
}

.item-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.folder-cell {
  cursor: pointer;
}

.folder-badge-icon {
  width: 26px;
  height: 26px;
  background: #f1f5f9;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #475569;
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
  color: #0f172a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-name.font-medium {
  font-weight: 500;
}

.type-text, .size-text, .date-text {
  font-size: 0.825rem;
  color: #64748b;
}

.more-action-btn {
  cursor: pointer;
  color: #94a3b8;
  padding: 4px 8px;
  border-radius: 4px;
  transition: all 0.2s;
}

.more-action-btn:hover {
  color: #0f172a;
  background-color: #f1f5f9;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.danger-item {
  color: #ef4444;
}
</style>

<!-- tooltip 挂到 body，需非 scoped -->
<style>
.space-desc-popper.el-popper {
  padding: 0 !important;
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  max-width: 360px;
}

.space-desc-popper .el-popper__arrow::before {
  background: #ffffff !important;
  border: 1px solid #e2e8f0 !important;
}

.space-desc-popper .desc-pop-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  box-shadow: 0 10px 28px -8px rgba(15, 23, 42, 0.14);
  padding: 12px 14px;
  min-width: 200px;
  max-width: 360px;
}

.space-desc-popper .desc-pop-label {
  font-size: 11px;
  font-weight: 600;
  color: #94a3b8;
  letter-spacing: 0.3px;
  margin-bottom: 6px;
}

.space-desc-popper .desc-pop-body {
  font-size: 13px;
  line-height: 1.55;
  color: #334155;
  word-break: break-word;
  white-space: pre-wrap;
}
</style>
