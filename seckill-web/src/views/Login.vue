<template>
  <el-row justify="center" class="login-row">
    <el-col :span="8">
      <el-card shadow="never">
        <h2 class="title">闪购节账号</h2>
        <el-tabs v-model="mode">
          <el-tab-pane label="登录" name="login">
            <el-form :model="loginForm" label-width="70px">
              <el-form-item label="用户名">
                <el-input v-model="loginForm.username" placeholder="请输入用户名" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入密码"
                  @keyup.enter="doLogin" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="submitting" class="full" @click="doLogin">登录</el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="注册" name="register">
            <el-form :model="regForm" label-width="70px">
              <el-form-item label="用户名">
                <el-input v-model="regForm.username" placeholder="唯一，登录用" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="regForm.password" type="password" show-password placeholder="登录密码" />
              </el-form-item>
              <el-form-item label="昵称">
                <el-input v-model="regForm.nickname" placeholder="展示昵称" />
              </el-form-item>
              <el-form-item label="手机号">
                <el-input v-model="regForm.phone" placeholder="可空" />
              </el-form-item>
              <el-form-item>
                <el-button type="success" :loading="submitting" class="full" @click="doRegister">注册并登录</el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>
        </el-tabs>

        <p class="redirect" v-if="redirect">
          返回<el-link type="primary" @click="$router.replace(redirect)">上一页</el-link>
        </p>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '../api'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const redirect = route.query.redirect

const mode = ref('login')
const submitting = ref(false)
const loginForm = ref({ username: '', password: '' })
const regForm = ref({ username: '', password: '', nickname: '', phone: '' })

function afterLogin(session) {
  auth.setSession({
    token: session.token,
    userId: session.userId,
    nickname: session.nickname,
    balance: session.balance
  })
  ElMessage.success('登录成功')
  router.replace(redirect || '/')
}

async function doLogin() {
  if (!loginForm.value.username || !loginForm.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  submitting.value = true
  try {
    const res = await userApi.login(loginForm.value)
    afterLogin(res.data)
  } catch (e) {
    // 4001-4009/401 已在请求层提示
  } finally {
    submitting.value = false
  }
}

async function doRegister() {
  if (!regForm.value.username || !regForm.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  submitting.value = true
  try {
    const res = await userApi.register(regForm.value)
    afterLogin(res.data)
  } catch (e) {
    // 4008 用户名已存在等在请求层提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-row { margin-top: 60px; }
.title { text-align: center; margin: 4px 0 16px; }
.full { width: 100%; }
.redirect { margin: 12px 0 0; text-align: center; color: #909399; }
</style>