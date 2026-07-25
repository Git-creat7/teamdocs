<template>
  <div class="login-page-container">
    <div class="login-wrapper">
      <!-- 顶部 Brand Header -->
      <div class="brand-header">
        <div class="brand-icon-box">
          <svg class="brand-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M19 3H9C7.89543 3 7 3.89543 7 5V19C7 20.1046 7.89543 21 9 21H19C20.1046 21 21 20.1046 21 19V5C21 3.89543 20.1046 3 19 3Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M3 7V17C3 18.1046 3.89543 19 5 19" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <span class="brand-name">TeamDocs</span>
      </div>

      <!-- 居中白色 B 端立体卡片 -->
      <div class="clean-login-card">
        <div class="card-header-clean">
          <h2 class="card-title-clean">
            {{ mode === 'login' ? '欢迎回来' : '注册账号' }}
          </h2>
          <p class="card-desc-clean">
            {{ mode === 'login' ? '使用账号密码进入你的团队空间' : '创建新账号开启团队文档协作' }}
          </p>
        </div>

        <!-- 表单区域 (仅 username 与 password) -->
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @keyup.enter="handleSubmit"
        >
          <el-form-item label="账号" prop="username">
            <el-input
              v-model.trim="form.username"
              placeholder="请输入账号 (2-16位)"
              maxlength="16"
              clearable
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码 (6-20位)"
              maxlength="20"
              show-password
            />
          </el-form-item>

          <!-- 提交按钮 -->
          <el-form-item>
            <button
              type="button"
              class="clean-submit-btn"
              :disabled="loading"
              @click="handleSubmit"
            >
              <el-icon v-if="loading" class="is-loading"><Loading /></el-icon>
              <span>{{ loading ? '处理中...' : (mode === 'login' ? '登 录' : '立 即 注 册') }}</span>
            </button>
          </el-form-item>
        </el-form>

        <!-- 模式切换入口 -->
        <div class="switch-mode-footer">
          <template v-if="mode === 'login'">
            <span>还没有账号？</span>
            <button type="button" class="switch-mode-btn" @click="switchMode('register')">
              注册账号
            </button>
          </template>
          <template v-else>
            <span>已有账号？</span>
            <button type="button" class="switch-mode-btn" @click="switchMode('login')">
              直接登录
            </button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { loginApi, registerApi } from '@/api/user'

const router = useRouter()

const mode = ref('login') // 'login' | 'register'
const loading = ref(false)
const formRef = ref(null)

const form = reactive({
  username: '',
  password: ''
})

// 表单校验规则
const rules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { min: 2, max: 16, message: '账号长度在 2 到 16 个字符之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度在 6 到 20 个字符之间', trigger: 'blur' }
  ]
}

function switchMode(newMode) {
  if (mode.value === newMode) return
  mode.value = newMode
  form.password = ''
  if (formRef.value) {
    formRef.value.clearValidate()
  }
}

async function handleSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (err) {
    return
  }

  loading.value = true

  try {
    if (mode.value === 'login') {
      const res = await loginApi({
        username: form.username,
        password: form.password
      })
      const token = typeof res === 'string' ? res : res?.token
      if (token) {
        localStorage.setItem('teamdocs_token', token)
        ElMessage.success('登录成功！欢迎使用 TeamDocs')
        router.replace('/spaces')
      } else {
        ElMessage.error('登录响应异常：未获取到有效 Token')
      }
    } else {
      // 注册逻辑
      await registerApi({
        username: form.username,
        password: form.password
      })
      ElMessage.success('注册成功，正在为您自动登录...')
      
      // 注册成功后自动调用登录
      const res = await loginApi({
        username: form.username,
        password: form.password
      })
      const token = typeof res === 'string' ? res : res?.token
      if (token) {
        localStorage.setItem('teamdocs_token', token)
        router.replace('/spaces')
      } else {
        ElMessage.error('自动登录异常：未获取到有效 Token')
      }
    }
  } catch (error) {
    // 错误在 request 拦截器中已有弹窗提示
  } finally {
    loading.value = false
  }
}
</script>
