<template>
  <div class="settings-page">
    <div class="settings-container">
      <header class="settings-head anim-item" style="--delay: 0">
        <h1>设置</h1>
        <p>管理账号信息和工作台偏好</p>
      </header>

      <div class="settings-layout anim-item" style="--delay: 1">
        <nav class="settings-nav" aria-label="设置分类">
          <button
            v-for="section in sections"
            :key="section.key"
            type="button"
            :class="['settings-nav-item', { active: activeSection === section.key }]"
            :aria-current="activeSection === section.key ? 'page' : undefined"
            @click="selectSection(section.key)"
          >
            <component :is="section.icon" :size="17" />
            <span>{{ section.label }}</span>
          </button>
        </nav>

        <main class="settings-content">
          <section v-if="activeSection === 'profile'" class="settings-section">
            <div class="section-head">
              <h2>个人资料</h2>
              <p>更新头像和对团队成员展示的信息</p>
            </div>

            <div class="avatar-row">
              <el-avatar :size="64" :src="userInfo?.avatar || undefined" class="profile-avatar">
                {{ avatarFallback }}
              </el-avatar>
              <div class="avatar-actions">
                <input
                  ref="avatarInputRef"
                  type="file"
                  accept="image/jpeg,image/png,image/gif,image/webp"
                  hidden
                  @change="handleAvatarSelected"
                />
                <el-button :loading="avatarUploading" @click="avatarInputRef?.click()">
                  更换头像
                </el-button>
                <span>JPG、PNG、GIF 或 WEBP，最大 2MB</span>
              </div>
            </div>

            <el-form label-position="top" class="settings-form">
              <el-form-item label="账号">
                <el-input :model-value="userInfo?.username || ''" disabled />
              </el-form-item>
              <el-form-item label="昵称">
                <el-input
                  v-model.trim="profileForm.nickname"
                  placeholder="未设置昵称"
                  maxlength="50"
                  clearable
                />
              </el-form-item>
              <el-form-item label="邮箱">
                <el-input
                  v-model.trim="profileForm.email"
                  placeholder="未绑定邮箱"
                  maxlength="100"
                  clearable
                />
              </el-form-item>
            </el-form>

            <div class="form-actions">
              <el-button type="primary" :loading="savingProfile" @click="handleSaveProfile">
                保存资料
              </el-button>
            </div>
          </section>

          <section v-else-if="activeSection === 'layout'" class="settings-section">
            <div class="section-head">
              <h2>外观与布局</h2>
              <p>调整侧栏在当前浏览器中的显示方式</p>
            </div>

            <div class="setting-list">
              <div class="setting-row">
                <div class="setting-copy">
                  <span class="setting-label">默认收起侧栏</span>
                  <span class="setting-description">进入工作台时使用紧凑侧栏</span>
                </div>
                <el-switch v-model="sidebarCollapsed" aria-label="默认收起侧栏" />
              </div>

              <div class="setting-row">
                <div class="setting-copy">
                  <span class="setting-label">查看详情时自动收起</span>
                  <span class="setting-description">为文档列表和预览区域释放横向空间</span>
                </div>
                <el-switch v-model="autoCollapseSidebar" aria-label="查看详情时自动收起侧栏" />
              </div>

              <div class="setting-row setting-row--stacked">
                <div class="setting-copy">
                  <span class="setting-label">侧栏宽度</span>
                  <span class="setting-description">{{ Math.round(sidebarWidth) }} px</span>
                </div>
                <div class="width-control">
                  <el-slider
                    v-model="sidebarWidth"
                    :min="SIDEBAR_MIN_WIDTH"
                    :max="SIDEBAR_MAX_WIDTH"
                    :step="8"
                    :show-tooltip="false"
                    aria-label="侧栏宽度"
                    @change="persistSidebarWidth"
                  />
                  <el-button :icon="RotateCcw" @click="handleResetSidebarWidth">
                    恢复默认
                  </el-button>
                </div>
              </div>
            </div>
          </section>

          <section v-else-if="activeSection === 'documents'" class="settings-section">
            <div class="section-head">
              <h2>搜索与文档</h2>
              <p>设置常用搜索范围和文档打开行为</p>
            </div>

            <div class="setting-list">
              <div class="setting-row">
                <div class="setting-copy">
                  <span class="setting-label">默认搜索范围</span>
                  <span class="setting-description">决定进入空间后顶部搜索框的默认范围</span>
                </div>
                <el-radio-group v-model="searchScopeMode" size="small">
                  <el-radio-button value="current">当前空间</el-radio-button>
                  <el-radio-button value="all">全部空间</el-radio-button>
                </el-radio-group>
              </div>

              <div class="setting-row">
                <div class="setting-copy">
                  <span class="setting-label">文档打开方式</span>
                  <span class="setting-description">从列表、最近浏览和团队动态打开文档时使用</span>
                </div>
                <el-radio-group v-model="documentOpenMode" size="small">
                  <el-radio-button value="workspace">工作台分栏</el-radio-button>
                  <el-radio-button value="new-tab">新标签页</el-radio-button>
                </el-radio-group>
              </div>

              <div class="setting-row">
                <div class="setting-copy">
                  <span class="setting-label">工作台默认页签</span>
                  <span class="setting-description">仅在工作台分栏打开文档时生效</span>
                </div>
                <el-radio-group
                  v-model="defaultDetailTab"
                  size="small"
                  :disabled="documentOpenMode === 'new-tab'"
                >
                  <el-radio-button value="preview">预览</el-radio-button>
                  <el-radio-button value="comments">评论</el-radio-button>
                </el-radio-group>
              </div>
            </div>
          </section>

          <section v-else class="settings-section">
            <div class="section-head">
              <h2>安全设置</h2>
              <p>修改登录密码并使已有登录状态失效</p>
            </div>

            <el-form
              ref="passwordFormRef"
              :model="passwordForm"
              :rules="passwordRules"
              label-position="top"
              class="settings-form"
            >
              <el-form-item label="当前密码" prop="oldPassword">
                <el-input
                  v-model="passwordForm.oldPassword"
                  type="password"
                  show-password
                  maxlength="20"
                  autocomplete="current-password"
                />
              </el-form-item>
              <el-form-item label="新密码" prop="newPassword">
                <el-input
                  v-model="passwordForm.newPassword"
                  type="password"
                  show-password
                  maxlength="20"
                  autocomplete="new-password"
                />
              </el-form-item>
            </el-form>

            <div class="security-note">
              修改成功后，所有设备都需要使用新密码重新登录。
            </div>
            <div class="form-actions">
              <el-button type="primary" :loading="savingPassword" @click="handleChangePassword">
                修改密码
              </el-button>
            </div>
          </section>
        </main>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import {
  PanelLeft,
  RotateCcw,
  ShieldCheck,
  SlidersHorizontal,
  UserRound
} from 'lucide-vue-next'
import { changePasswordApi, updateAvatarApi, updateProfileApi } from '@/api/user'
import { resetAllStores, usePreferencesStore, useUserStore } from '@/stores'
import { SIDEBAR_MAX_WIDTH, SIDEBAR_MIN_WIDTH } from '@/stores/preferences'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const preferences = usePreferencesStore()
const { userInfo } = storeToRefs(userStore)
const {
  sidebarCollapsed,
  sidebarWidth,
  autoCollapseSidebar,
  searchScopeMode,
  documentOpenMode,
  defaultDetailTab
} = storeToRefs(preferences)

const sections = [
  { key: 'profile', label: '个人资料', icon: UserRound },
  { key: 'layout', label: '外观与布局', icon: PanelLeft },
  { key: 'documents', label: '搜索与文档', icon: SlidersHorizontal },
  { key: 'security', label: '安全设置', icon: ShieldCheck }
]
const sectionKeys = sections.map((item) => item.key)
const activeSection = ref(sectionKeys.includes(route.query.section) ? route.query.section : 'profile')

const avatarInputRef = ref(null)
const avatarUploading = ref(false)
const profileForm = reactive({ nickname: '', email: '' })
const savingProfile = ref(false)

const passwordFormRef = ref(null)
const passwordForm = reactive({ oldPassword: '', newPassword: '' })
const savingPassword = ref(false)
const passwordRules = {
  oldPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度在 6 到 20 个字符之间', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度在 6 到 20 个字符之间', trigger: 'blur' }
  ]
}

const displayName = computed(() =>
  userInfo.value?.nickname || userInfo.value?.username || '团队成员'
)
const avatarFallback = computed(() => displayName.value.charAt(0).toUpperCase() || 'U')

watch(userInfo, (value) => {
  if (!value) return
  profileForm.nickname = value.nickname || ''
  profileForm.email = value.email || ''
}, { immediate: true })

watch(() => route.query.section, (section) => {
  if (sectionKeys.includes(section)) activeSection.value = section
})

onMounted(() => {
  if (!userInfo.value) userStore.refresh()
})

function selectSection(section) {
  activeSection.value = section
  router.replace({ query: { ...route.query, section } })
}

async function handleSaveProfile() {
  savingProfile.value = true
  try {
    const data = await updateProfileApi({
      nickname: profileForm.nickname,
      email: profileForm.email
    })
    userStore.setUser(data)
    ElMessage.success('资料已更新')
  } catch (err) {
    // 业务错误由请求拦截器统一提示。
  } finally {
    savingProfile.value = false
  }
}

async function handleAvatarSelected(event) {
  const file = event.target.files?.[0]
  if (event.target) event.target.value = ''
  if (!file) return

  const allowed = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowed.includes(file.type)) {
    ElMessage.error('头像仅支持 JPG、PNG、GIF、WEBP')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('头像大小不能超过 2MB')
    return
  }

  avatarUploading.value = true
  try {
    const data = await updateAvatarApi(file)
    userStore.setUser(data)
    ElMessage.success('头像已更新')
  } catch (err) {
    // 业务错误由请求拦截器统一提示。
  } finally {
    avatarUploading.value = false
  }
}

async function handleChangePassword() {
  if (!passwordFormRef.value) return
  try {
    await passwordFormRef.value.validate()
  } catch (err) {
    return
  }

  savingPassword.value = true
  try {
    await changePasswordApi(passwordForm)
    ElMessage.success('密码修改成功，请重新登录')
    localStorage.removeItem('teamdocs_token')
    resetAllStores()
    router.replace('/login')
  } catch (err) {
    // 业务错误由请求拦截器统一提示。
  } finally {
    savingPassword.value = false
  }
}

function handleResetSidebarWidth() {
  preferences.resetSidebarWidth()
  ElMessage.success('侧栏宽度已恢复默认')
}

function persistSidebarWidth() {
  preferences.persistSidebarWidth()
}
</script>

<style scoped>
.settings-page {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 32px 40px 48px;
  background: var(--app-bg);
}

.settings-container {
  width: 100%;
  max-width: 1040px;
  margin: 0 auto;
}

.settings-head {
  margin-bottom: 28px;
}

.settings-head h1 {
  margin: 0 0 6px;
  font-size: 1.5rem;
  color: var(--app-text);
}

.settings-head p,
.section-head p {
  margin: 0;
  font-size: 0.86rem;
  color: var(--app-text-muted);
}

.settings-layout {
  display: grid;
  grid-template-columns: 176px minmax(0, 1fr);
  gap: 32px;
  align-items: start;
}

.settings-nav {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.settings-nav-item {
  min-height: 40px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--app-text-muted);
  font: inherit;
  font-size: 0.875rem;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease-standard),
              color var(--dur-fast) var(--ease-standard);
}

.settings-nav-item:hover {
  color: var(--app-text);
  background: var(--app-hover);
}

.settings-nav-item:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.settings-nav-item.active {
  color: var(--app-accent);
  background: var(--app-accent-weak);
  font-weight: 600;
}

.settings-content {
  min-width: 0;
  min-height: 520px;
  padding-left: 32px;
  border-left: 1px solid var(--app-border);
}

.settings-section {
  width: 100%;
  max-width: 680px;
}

.section-head {
  padding-bottom: 18px;
  margin-bottom: 24px;
  border-bottom: 1px solid var(--app-border);
}

.section-head h2 {
  margin: 0 0 6px;
  font-size: 1.08rem;
  color: var(--app-text);
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 24px;
}

.profile-avatar {
  color: #ffffff;
  font-weight: 700;
  background: #4f46e5;
  flex-shrink: 0;
}

.avatar-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 7px;
}

.avatar-actions span,
.setting-description,
.security-note {
  font-size: 0.78rem;
  line-height: 1.5;
  color: var(--app-text-faint);
}

.settings-form {
  max-width: 520px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  max-width: 520px;
  padding-top: 4px;
}

.setting-list {
  display: flex;
  flex-direction: column;
}

.setting-row {
  min-height: 74px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 24px;
  padding: 16px 0;
  border-bottom: 1px solid var(--app-border-soft);
}

.setting-row:first-child {
  padding-top: 0;
}

.setting-row--stacked {
  grid-template-columns: 1fr;
  gap: 12px;
}

.setting-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.setting-label {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--app-text-2);
}

.width-control {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto;
  align-items: center;
  gap: 20px;
}

.security-note {
  max-width: 520px;
  margin: -4px 0 18px;
}

@media (max-width: 768px) {
  .settings-page {
    padding: 20px 16px 32px;
  }

  .settings-head {
    margin-bottom: 20px;
  }

  .settings-layout {
    grid-template-columns: 1fr;
    gap: 24px;
  }

  .settings-nav {
    position: static;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .settings-nav-item {
    justify-content: center;
    padding-inline: 8px;
  }

  .settings-content {
    min-height: 0;
    padding: 24px 0 0;
    border-top: 1px solid var(--app-border);
    border-left: 0;
  }

  .setting-row {
    grid-template-columns: 1fr;
    gap: 12px;
    align-items: start;
  }

  .setting-row > :last-child {
    justify-self: start;
  }

  .setting-row :deep(.el-radio-group) {
    width: 100%;
    display: flex;
  }

  .setting-row :deep(.el-radio-button) {
    flex: 1;
  }

  .setting-row :deep(.el-radio-button__inner) {
    width: 100%;
    padding-inline: 8px;
  }

  .width-control {
    width: 100%;
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .width-control .el-button {
    justify-self: start;
  }

  .avatar-row {
    align-items: flex-start;
  }
}
</style>
