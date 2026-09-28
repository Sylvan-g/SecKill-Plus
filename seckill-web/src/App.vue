<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="brand" @click="$router.push('/')">⚡ 新果商城 · 超级闪购节</div>
      <div class="nav">
        <router-link to="/">闪购频道</router-link>
        <router-link to="/orders">我的订单</router-link>
        <router-link to="/wallet">钱包</router-link>
        <router-link to="/admin/activity">运营端</router-link>
      </div>
      <div class="user">
        <template v-if="auth.token">
          <span class="nickname">{{ auth.nickname || auth.userId }}</span>
          <el-button size="small" @click="logout">退出</el-button>
        </template>
        <el-button v-else size="small" type="primary" @click="$router.push('/login')">登录 / 注册</el-button>
      </div>
    </el-header>
    <el-main><router-view /></el-main>
  </el-container>
</template>

<script setup>
import { useAuthStore } from './stores/auth'
import { ElMessage } from 'element-plus'
import router from './router'

const auth = useAuthStore()

async function logout() {
  await auth.logout()
  ElMessage.success('已退出登录')
  router.push('/')
}
</script>

<style>
body { margin: 0; background: #f5f7fa; }
.app-shell { min-height: 100vh; }
.app-header {
  display: flex; align-items: center; gap: 24px;
  background: #fff; border-bottom: 1px solid #ebeef5;
}
.brand { font-size: 18px; font-weight: 700; color: #e63946; cursor: pointer; }
.nav { flex: 1; display: flex; gap: 18px; }
.nav a { color: #303133; text-decoration: none; }
.nav a.router-link-active { color: #e63946; font-weight: 600; }
.user { display: flex; align-items: center; gap: 8px; }
.nickname { color: #606266; }
</style>