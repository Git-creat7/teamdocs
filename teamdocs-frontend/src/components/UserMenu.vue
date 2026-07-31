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
          <el-dropdown-item command="settings">
            <el-icon><Settings /></el-icon>设置
          </el-dropdown-item>
          <el-dropdown-item command="logout" divided>
            <el-icon><SwitchButton /></el-icon>退出登录
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChevronDown as ArrowDown, LogOut as SwitchButton, Settings } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { logoutApi } from '@/api/user'
import { useUserStore, resetAllStores } from '@/stores'

const router = useRouter()
const userStore = useUserStore()
const { userInfo } = storeToRefs(userStore)

const displayName = computed(() =>
  userInfo.value?.nickname || userInfo.value?.username || '团队成员'
)

const avatarFallback = computed(() => {
  const name = displayName.value
  return name ? name.charAt(0).toUpperCase() : 'U'
})

onMounted(() => {
  if (!userInfo.value) userStore.refresh()
})

function handleCommand(command) {
  if (command === 'settings') {
    router.push('/settings')
  } else if (command === 'logout') {
    handleLogout()
  }
}

async function handleLogout() {
  try {
    await logoutApi()
    ElMessage.success('已安全退出登录')
  } catch (err) {
    // 退出时即使服务端不可用，也要清理本地登录态。
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

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 8px;
  color: var(--app-text-2);
  cursor: pointer;
  outline: none;
  transition: background-color var(--dur-fast) var(--ease-standard);
}

.user-trigger:hover {
  background-color: var(--app-hover);
}

.user-avatar {
  color: #ffffff;
  font-weight: 600;
  background: #4f46e5;
  flex-shrink: 0;
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  font-size: 0.875rem;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.caret-icon {
  font-size: 12px;
  color: var(--app-text-faint);
}

@media (max-width: 768px) {
  .user-name,
  .caret-icon {
    display: none;
  }

  .user-trigger {
    padding: 4px;
  }
}
</style>
