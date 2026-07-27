<template>
  <el-drawer
    v-model="visible"
    title="空间成员"
    size="420px"
    destroy-on-close
    append-to-body
  >
    <!-- 添加成员 (OWNER/ADMIN 可见) -->
    <div v-if="canManage" class="add-member-box">
      <div class="add-member-row">
        <el-input
          v-model.trim="addForm.username"
          placeholder="输入对方账号"
          maxlength="16"
          clearable
          class="add-input"
          @keyup.enter="handleAddMember"
        />
        <el-select v-model="addForm.role" class="add-role-select">
          <el-option label="成员" value="MEMBER" />
          <el-option label="管理员" value="ADMIN" />
        </el-select>
        <el-button type="primary" :loading="adding" @click="handleAddMember">
          添加
        </el-button>
      </div>
      <p class="add-hint">对方需要先注册 TeamDocs 账号</p>
    </div>

    <!-- 成员列表 (OWNER 置顶 → ADMIN → MEMBER，同角色按加入时间) -->
    <div v-loading="loading" class="member-list">
      <div v-for="m in sortedMembers" :key="m.id" class="member-item">
        <el-avatar :size="34" :src="m.avatar || undefined" class="member-avatar">
          {{ (m.username || 'U').charAt(0).toUpperCase() }}
        </el-avatar>
        <div class="member-info">
          <div class="member-name-row">
            <span class="member-name">{{ m.username }}</span>
            <span v-if="m.userId === currentUserId" class="me-badge">我</span>
          </div>
          <span class="member-joined">加入于 {{ formatDateTime(m.joinedAt) }}</span>
        </div>

        <!-- 角色区：OWNER 固定展示；其余按权限展示下拉或文本 -->
        <div class="member-role-area">
          <el-tag v-if="m.role === 'OWNER'" type="warning" effect="plain" size="small">
            所有者
          </el-tag>
          <el-select
            v-else-if="canChangeRole(m)"
            :model-value="m.role"
            size="small"
            class="role-select"
            @change="(val) => handleRoleChange(m, val)"
          >
            <el-option label="管理员" value="ADMIN" />
            <el-option label="成员" value="MEMBER" />
          </el-select>
          <el-tag v-else :type="m.role === 'ADMIN' ? 'primary' : 'info'" effect="plain" size="small">
            {{ m.role === 'ADMIN' ? '管理员' : '成员' }}
          </el-tag>

          <el-button
            v-if="canRemove(m)"
            link
            type="danger"
            size="small"
            @click="handleRemove(m)"
          >
            移除
          </el-button>
        </div>
      </div>

      <EmptyState
        v-if="!loading && members.length === 0"
        :icon="Users"
        title="暂无成员"
        description="通过上方输入框邀请伙伴加入空间"
      />
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Users } from 'lucide-vue-next'
import { addMemberApi, removeMemberApi, updateMemberRoleApi } from '@/api/space'
import { formatDateTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'

const props = defineProps({
  spaceId: { type: [Number, String], required: true },
  members: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  myRole: { type: String, default: '' },
  currentUserId: { type: [Number, String], default: null }
})

const emit = defineEmits(['refresh'])

const visible = defineModel({ type: Boolean, default: false })

const adding = ref(false)
const addForm = reactive({ username: '', role: 'MEMBER' })

const canManage = computed(() => props.myRole === 'OWNER' || props.myRole === 'ADMIN')

const ROLE_ORDER = { OWNER: 0, ADMIN: 1, MEMBER: 2 }
const sortedMembers = computed(() =>
  [...props.members].sort((a, b) => {
    const r = (ROLE_ORDER[a.role] ?? 9) - (ROLE_ORDER[b.role] ?? 9)
    if (r !== 0) return r
    return new Date(a.joinedAt || 0) - new Date(b.joinedAt || 0)
  })
)

function canChangeRole(member) {
  // 后端约束：仅 OWNER 可改角色，且不能改自己、不能设 OWNER
  return props.myRole === 'OWNER' && member.userId !== props.currentUserId
}

function canRemove(member) {
  if (member.role === 'OWNER') return false
  if (props.myRole === 'OWNER') return true
  // ADMIN 只能移除 MEMBER
  return props.myRole === 'ADMIN' && member.role === 'MEMBER'
}

async function handleAddMember() {
  if (!addForm.username) {
    ElMessage.warning('请输入对方账号')
    return
  }
  adding.value = true
  try {
    await addMemberApi(props.spaceId, {
      username: addForm.username,
      role: addForm.role
    })
    ElMessage.success('成员添加成功')
    addForm.username = ''
    addForm.role = 'MEMBER'
    emit('refresh')
  } catch (err) {
    // 拦截器处理
  } finally {
    adding.value = false
  }
}

async function handleRoleChange(member, newRole) {
  if (newRole === member.role) return
  try {
    await updateMemberRoleApi(props.spaceId, member.userId, newRole)
    ElMessage.success(`已将 ${member.username} 设为${newRole === 'ADMIN' ? '管理员' : '成员'}`)
    emit('refresh')
  } catch (err) {
    emit('refresh')
  }
}

function handleRemove(member) {
  ElMessageBox.confirm(
    `确定将 "${member.username}" 移出该空间吗？`,
    '移除成员',
    {
      confirmButtonText: '移除',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(async () => {
    try {
      await removeMemberApi(props.spaceId, member.userId)
      ElMessage.success('成员已移除')
      emit('refresh')
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}
</script>

<style scoped>
.add-member-box {
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--app-border-soft);
  margin-bottom: 1rem;
}

.add-member-row {
  display: flex;
  gap: 8px;
}

.add-input {
  flex: 1;
}

.add-role-select {
  width: 96px;
  flex-shrink: 0;
}

.add-hint {
  font-size: 0.75rem;
  color: var(--app-text-faint);
  margin: 6px 0 0;
}

.member-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 120px;
}

.member-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-radius: 8px;
  transition: background-color 0.15s;
}

.member-item:hover {
  background-color: var(--app-hover-soft);
}

.member-avatar {
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  color: #ffffff;
  font-weight: 600;
  flex-shrink: 0;
}

.member-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.member-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.member-name {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.me-badge {
  font-size: 0.7rem;
  color: var(--app-accent);
  background: var(--app-accent-weak);
  border-radius: 4px;
  padding: 0 5px;
  flex-shrink: 0;
}

.member-joined {
  font-size: 0.75rem;
  color: var(--app-text-faint);
}

.member-role-area {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.role-select {
  width: 92px;
}
</style>
