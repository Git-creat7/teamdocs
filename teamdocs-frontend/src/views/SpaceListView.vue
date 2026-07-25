<template>
  <div class="space-page-container">
    <!-- 顶部导航栏 (Logo + 用户区/退出) -->
    <header class="navbar">
      <div class="nav-brand">
        <div class="nav-logo">
          <svg viewBox="0 0 24 24" fill="none" class="logo-svg">
            <path d="M19 3H9C7.89543 3 7 3.89543 7 5V19C7 20.1046 7.89543 21 9 21H19C20.1046 21 21 20.1046 21 19V5C21 3.89543 20.1046 3 19 3Z" stroke="white" stroke-width="2"/>
            <path d="M3 7V17C3 18.1046 3.89543 19 5 19" stroke="white" stroke-width="2"/>
          </svg>
        </div>
        <span class="nav-title">TeamDocs</span>
      </div>

      <div class="nav-user">
        <span class="username">{{ userInfo?.username || '团队成员' }}</span>
        <el-button type="info" plain size="small" @click="handleLogout">
          退出登录
        </el-button>
      </div>
    </header>

    <!-- 主区 (对齐参考图主区：标题 + 新建按钮 + 3列卡片 + 尾部虚线新建位) -->
    <main class="main-content">
      <div class="content-container">
        <!-- 标题行：左侧「我的空间」，右侧「新建空间」 -->
        <div class="page-header">
          <h1 class="page-title">我的空间</h1>
          <el-button type="primary" class="create-btn" @click="openCreateDialog">
            <el-icon class="btn-icon"><Plus /></el-icon>
            新建空间
          </el-button>
        </div>

        <!-- 骨架屏加载中 -->
        <div v-if="loading" class="grid-layout">
          <div v-for="i in 3" :key="i" class="skeleton-card">
            <el-skeleton :rows="3" animated />
          </div>
        </div>

        <!-- 空间卡片 3列网格 -->
        <div v-else class="grid-layout">
          <!-- 真实空间卡片 -->
          <div
            v-for="(space, index) in spaces"
            :key="space.id"
            class="space-card"
            @click="enterSpace(space)"
          >
            <div class="card-header-row">
              <div :class="['card-icon-box', getIconStyleClass(index)]">
                <el-icon :size="18"><FolderOpened /></el-icon>
              </div>
              <h3 class="space-name" :title="space.name">{{ space.name }}</h3>
            </div>
            
            <el-tooltip
              placement="top"
              :disabled="!space.description"
              :show-after="180"
              :hide-after="0"
              popper-class="space-desc-popper"
            >
              <template #content>
                <div class="desc-pop-card">
                  <div class="desc-pop-label">空间描述</div>
                  <div class="desc-pop-body">{{ space.description }}</div>
                </div>
              </template>
              <p class="space-desc">
                {{ space.description || '暂无描述' }}
              </p>
            </el-tooltip>

            <div class="card-footer">
              <span class="date-text">更新于 {{ formatDate(space.createdAt) }}</span>
            </div>
          </div>

          <!-- 右下角「+ 创建新空间」虚线卡片 -->
          <div class="create-card-dashed" @click="openCreateDialog">
            <div class="dashed-inner">
              <el-icon :size="20" class="plus-icon"><Plus /></el-icon>
              <span class="dashed-text">创建新空间</span>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- 新建空间对话框 -->
    <el-dialog
      v-model="createDialogVisible"
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
          <el-button @click="createDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="handleCreateSpace">
            创建
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, FolderOpened } from '@element-plus/icons-vue'
import { getUserInfoApi, logoutApi } from '@/api/user'
import { listMySpacesApi, createSpaceApi } from '@/api/space'

const router = useRouter()

const loading = ref(true)
const spaces = ref([])
const userInfo = ref(null)

// 新建空间 Dialog 状态
const createDialogVisible = ref(false)
const submitting = ref(false)
const createFormRef = ref(null)

const createForm = reactive({
  name: '',
  description: ''
})

const createRules = {
  name: [
    { required: true, message: '请输入空间名称', trigger: 'blur' },
    { min: 1, max: 64, message: '空间名称长度在 1 到 64 个字符', trigger: 'blur' }
  ],
  description: [
    { max: 255, message: '空间描述最长 255 个字符', trigger: 'blur' }
  ]
}

const iconColorClasses = ['theme-blue', 'theme-purple', 'theme-green', 'theme-amber', 'theme-cyan', 'theme-rose']

function getIconStyleClass(index) {
  return iconColorClasses[index % iconColorClasses.length]
}

onMounted(() => {
  fetchUserInfo()
  loadSpaces()
})

async function fetchUserInfo() {
  try {
    const data = await getUserInfoApi()
    userInfo.value = data
  } catch (err) {
    // 忽略异常
  }
}

// 真实打后端 GET /space/list
async function loadSpaces() {
  loading.value = true
  try {
    const list = await listMySpacesApi()
    spaces.value = Array.isArray(list) ? list : []
  } catch (err) {
    spaces.value = []
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createForm.name = ''
  createForm.description = ''
  createDialogVisible.value = true
}

// 创建空间：提交 POST /space 成功后，重新打 GET /space/list 刷新服务器真数据
async function handleCreateSpace() {
  if (!createFormRef.value) return
  try {
    await createFormRef.value.validate()
  } catch (err) {
    return
  }

  submitting.value = true

  try {
    await createSpaceApi({
      name: createForm.name.trim(),
      description: createForm.description ? createForm.description.trim() : ''
    })
    ElMessage.success('空间创建成功')
    createDialogVisible.value = false
    await loadSpaces()
  } catch (error) {
    // 错误在 request 拦截器中已有弹框提示
  } finally {
    submitting.value = false
  }
}

function enterSpace(space) {
  if (space && space.id) {
    router.push(`/spaces/${space.id}`)
  }
}

async function handleLogout() {
  try {
    await logoutApi()
    ElMessage.success('已安全退出登录')
  } catch (err) {
    // 忽略异常
  } finally {
    localStorage.removeItem('teamdocs_token')
    router.replace('/login')
  }
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return '-'
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${year}/${month}/${day}`
}
</script>

<style scoped>
.space-page-container {
  min-height: 100vh;
  background-color: #f8fafc;
  display: flex;
  flex-direction: column;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.navbar {
  height: 56px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2rem;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.nav-logo {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.logo-svg {
  width: 18px;
  height: 18px;
}

.nav-title {
  font-size: 1.15rem;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.3px;
}

.nav-user {
  display: flex;
  align-items: center;
  gap: 16px;
}

.username {
  font-size: 0.875rem;
  color: #475569;
  font-weight: 500;
}

.main-content {
  flex: 1;
  padding: 2.5rem 2rem;
  display: flex;
  justify-content: center;
}

.content-container {
  max-width: 1120px;
  width: 100%;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2rem;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.create-btn {
  background-color: #2563eb;
  border-color: #2563eb;
  border-radius: 8px;
  font-weight: 500;
  padding: 9px 18px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.create-btn:hover {
  background-color: #1d4ed8;
  border-color: #1d4ed8;
}

/* 网格系统 (对齐参考图 3 列网格) */
.grid-layout {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem;
}

@media (max-width: 992px) {
  .grid-layout {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 640px) {
  .grid-layout {
    grid-template-columns: 1fr;
  }
}

.skeleton-card {
  background: #ffffff;
  padding: 1.5rem;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
}

/* 空间卡片 */
.space-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 1.5rem;
  cursor: pointer;
  transition: all 0.2s ease-in-out;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 150px;
  box-sizing: border-box;
}

.space-card:hover {
  border-color: #cbd5e1;
  box-shadow: 0 8px 20px -4px rgba(15, 23, 42, 0.06);
  transform: translateY(-2px);
}

.card-header-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 0.85rem;
}

.card-icon-box {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* 图标主题颜色对齐效果图 */
.theme-blue {
  background-color: #eff6ff;
  color: #3b82f6;
}

.theme-purple {
  background-color: #f5f3ff;
  color: #8b5cf6;
}

.theme-green {
  background-color: #f0fdf4;
  color: #22c55e;
}

.theme-amber {
  background-color: #fefce8;
  color: #eab308;
}

.theme-cyan {
  background-color: #ecfeff;
  color: #06b6d4;
}

.theme-rose {
  background-color: #fff1f2;
  color: #f43f5e;
}

.space-name {
  font-size: 1.05rem;
  font-weight: 600;
  color: #0f172a;
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.space-desc {
  font-size: 0.875rem;
  color: #64748b;
  margin: 0 0 1.25rem 0;
  line-height: 1.5;
  max-width: 100%;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  word-break: break-word;
  cursor: default;
  min-height: 2.7em;
}

.card-footer {
  border-top: 1px solid #f1f5f9;
  padding-top: 0.75rem;
}

.date-text {
  font-size: 0.8rem;
  color: #94a3b8;
}

/* 右下角「+ 创建新空间」虚线卡片 (参考效果图) */
.create-card-dashed {
  background: #ffffff;
  border: 1.5px dashed #cbd5e1;
  border-radius: 12px;
  min-height: 150px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s ease;
  box-sizing: border-box;
}

.create-card-dashed:hover {
  border-color: #2563eb;
  background-color: #f8fafc;
}

.dashed-inner {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #64748b;
  font-weight: 500;
  font-size: 0.95rem;
  transition: color 0.2s ease;
}

.create-card-dashed:hover .dashed-inner {
  color: #2563eb;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>

<!-- tooltip 挂到 body，需非 scoped -->
<style>
.space-desc-popper.el-popper {
  padding: 0 !important;
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  max-width: 320px;
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
  min-width: 180px;
  max-width: 320px;
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
