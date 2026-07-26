<template>
  <div class="user-menu-root">
    <el-dropdown trigger="click" @command="handleCommand">
      <div class="user-trigger">
        <el-avatar :size="30" :src="userInfo?.avatar || undefined" class="user-avatar">
          {{ avatarFallback }}
        </el-avatar>
        <span class="user-name">{{ displayName }}</span>
        <el-icon class="caret-icon"><ArrowDown /></el-icon>
      </div>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="profile">
            <el-icon><User /></el-icon>个人资料
          </el-dropdown-item>
          <!-- 主题切换：与登录页共用同一 store，全站生效 -->
          <div class="theme-row" @click.stop>
            <el-icon class="theme-row-icon"><Palette /></el-icon>
            <div class="theme-swatches">
              <button
                v-for="t in themeOptions"
                :key="t.key"
                type="button"
                :class="['swatch', { active: theme === t.key }]"
                :style="{ background: t.swatch }"
                :title="t.name"
                @click="setTheme(t.key)"
              ></button>
            </div>
          </div>
          <el-dropdown-item command="logout" divided>
            <el-icon><SwitchButton /></el-icon>退出登录
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <!-- 个人资料对话框 -->
    <el-dialog
      v-model="profileVisible"
      title="个人资料"
      width="460px"
      destroy-on-close
      append-to-body
    >
      <el-tabs v-model="activeTab">
        <!-- 基本资料 -->
        <el-tab-pane label="基本资料" name="profile">
          <div class="avatar-row">
            <el-avatar :size="56" :src="userInfo?.avatar || undefined">
              {{ avatarFallback }}
            </el-avatar>
            <div class="avatar-actions">
              <input
                ref="avatarInputRef"
                type="file"
                accept="image/jpeg,image/png,image/gif,image/webp"
                style="display: none"
                @change="handleAvatarSelected"
              />
              <el-button size="small" :loading="avatarUploading" @click="avatarInputRef?.click()">
                更换头像
              </el-button>
              <span class="avatar-hint">JPG/PNG/GIF/WEBP，不超过 2MB</span>
            </div>
          </div>

          <el-form label-position="top" class="profile-form">
            <el-form-item label="账号">
              <el-input :model-value="userInfo?.username" disabled />
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
          <div class="pane-footer">
            <el-button type="primary" :loading="savingProfile" @click="handleSaveProfile">
              保存资料
            </el-button>
          </div>
        </el-tab-pane>

        <!-- 修改密码 -->
        <el-tab-pane label="修改密码" name="password">
          <el-form
            ref="pwdFormRef"
            :model="pwdForm"
            :rules="pwdRules"
            label-position="top"
          >
            <el-form-item label="当前密码" prop="oldPassword">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password maxlength="20" />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="pwdForm.newPassword" type="password" show-password maxlength="20" />
            </el-form-item>
          </el-form>
          <p class="pwd-hint">修改成功后所有设备会退出登录，需要用新密码重新登录。</p>
          <div class="pane-footer">
            <el-button type="primary" :loading="savingPwd" @click="handleChangePassword">
              确认修改
            </el-button>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChevronDown as ArrowDown, User, LogOut as SwitchButton, Palette } from 'lucide-vue-next'
import { useThemeStore } from '@/stores/theme'
import {
  updateProfileApi,
  changePasswordApi,
  updateAvatarApi,
  logoutApi
} from '@/api/user'
import { storeToRefs } from 'pinia'
import { useUserStore, resetAllStores } from '@/stores'

const router = useRouter()

const userStore = useUserStore()
const { userInfo } = storeToRefs(userStore)
const refreshUser = userStore.refresh
const setUser = userStore.setUser

const themeStore = useThemeStore()
const { theme } = storeToRefs(themeStore)
const setTheme = themeStore.setTheme

const themeOptions = [
  { key: 'day', name: 'Day', swatch: '#f4f4f5' },
  { key: 'night', name: 'Night', swatch: '#111827' },
  { key: 'coffee', name: 'Coffee', swatch: '#b45309' },
  { key: 'sakura', name: 'Sakura', swatch: '#f9a8d4' },
  { key: 'cyberpunk', name: 'Cyberpunk', swatch: '#4ade80' }
]
const profileVisible = ref(false)
const activeTab = ref('profile')

const avatarInputRef = ref(null)
const avatarUploading = ref(false)

const profileForm = reactive({ nickname: '', email: '' })
const savingProfile = ref(false)

const pwdFormRef = ref(null)
const pwdForm = reactive({ oldPassword: '', newPassword: '' })
const savingPwd = ref(false)

const pwdRules = {
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

const avatarFallback = computed(() => {
  const name = displayName.value
  return name ? name.charAt(0).toUpperCase() : 'U'
})

onMounted(() => {
  if (!userInfo.value) refreshUser()
})

function handleCommand(cmd) {
  if (cmd === 'profile') {
    profileForm.nickname = userInfo.value?.nickname || ''
    profileForm.email = userInfo.value?.email || ''
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    activeTab.value = 'profile'
    profileVisible.value = true
  } else if (cmd === 'logout') {
    handleLogout()
  }
}

async function handleSaveProfile() {
  savingProfile.value = true
  try {
    const data = await updateProfileApi({
      nickname: profileForm.nickname,
      email: profileForm.email
    })
    setUser(data)
    ElMessage.success('资料已更新')
  } catch (err) {
    // 拦截器处理
  } finally {
    savingProfile.value = false
  }
}

async function handleChangePassword() {
  if (!pwdFormRef.value) return
  try {
    await pwdFormRef.value.validate()
  } catch (err) {
    return
  }
  savingPwd.value = true
  try {
    await changePasswordApi({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword
    })
    ElMessage.success('密码修改成功，请重新登录')
    localStorage.removeItem('teamdocs_token')
    resetAllStores()
    router.replace('/login')
  } catch (err) {
    // 拦截器处理
  } finally {
    savingPwd.value = false
  }
}

async function handleAvatarSelected(event) {
  const file = event.target.files?.[0]
  if (event?.target) event.target.value = ''
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
    setUser(data)
    ElMessage.success('头像已更新')
  } catch (err) {
    // 拦截器处理
  } finally {
    avatarUploading.value = false
  }
}

async function handleLogout() {
  try {
    await logoutApi()
    ElMessage.success('已安全退出登录')
  } catch (err) {
    // 忽略
  } finally {
    localStorage.removeItem('teamdocs_token')
    resetAllStores()
    router.replace('/login')
  }
}
</script>

<style scoped>
.user-menu-root {
  display: flex;
  align-items: center;
}

.theme-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 16px;
  cursor: default;
}

.theme-row-icon {
  font-size: 14px;
  color: var(--app-text-muted, #64748b);
}

.theme-swatches {
  display: flex;
  gap: 6px;
}

.swatch {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid rgba(127, 127, 127, 0.35);
  padding: 0;
  cursor: pointer;
  transition: transform var(--dur-fast, 120ms) var(--ease-standard, ease);
}

.swatch:hover {
  transform: scale(1.18);
}

.swatch.active {
  box-shadow: 0 0 0 2px var(--app-panel, #fff), 0 0 0 4px var(--app-accent, #2563eb);
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
  transition: background-color 0.2s;
  outline: none;
}

.user-trigger:hover {
  background-color: var(--app-hover);
}

.user-avatar {
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  color: #ffffff;
  font-weight: 600;
  flex-shrink: 0;
}

.user-name {
  font-size: 0.875rem;
  color: var(--app-text-2);
  font-weight: 500;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.caret-icon {
  font-size: 12px;
  color: var(--app-text-faint);
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 1rem;
}

.avatar-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.avatar-hint {
  font-size: 0.75rem;
  color: var(--app-text-faint);
}

.profile-form {
  margin-top: 0.25rem;
}

.pwd-hint {
  font-size: 0.8rem;
  color: var(--app-text-faint);
  margin: 0.25rem 0 0.75rem;
}

.pane-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 0.5rem;
}
</style>
