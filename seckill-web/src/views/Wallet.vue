<template>
  <div class="wallet">
    <el-row :gutter="20">
      <el-col :span="10">
        <el-card shadow="never">
          <h2>我的钱包</h2>
          <p class="balance-label">可用余额</p>
          <p class="balance">¥{{ auth.balance ?? '--' }}</p>
          <el-button type="primary" @click="$router.push('/orders')">去支付订单</el-button>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never">
          <h3>模拟充值（演示，不接真实支付）</h3>
          <el-form inline>
            <el-form-item label="金额">
              <el-input-number v-model="amount" :min="1" :step="50" :precision="0" />
            </el-form-item>
            <el-form-item>
              <el-button type="success" :loading="loading" @click="recharge">充值</el-button>
            </el-form-item>
          </el-form>
          <p class="tip">需求 8.7：写钱包流水入门（type=2），返回充值后余额</p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const amount = ref(100)
const loading = ref(false)

async function refreshBalance() {
  try {
    const res = await userApi.balance()
    auth.setBalance(res.data.balance)
  } catch (e) {
    // 401 已在请求层提示（此时 balance 保持）
  }
}

async function recharge() {
  loading.value = true
  try {
    const res = await userApi.recharge(amount.value)
    auth.setBalance(res.data.balance)
    ElMessage.success(`充值成功，当前余额 ¥${res.data.balance}`)
  } catch (e) {
    // 已在请求层提示
  } finally {
    loading.value = false
  }
}

onMounted(refreshBalance)
</script>

<style scoped>
.balance-label { color: #909399; margin: 12px 0 0; }
.balance { font-size: 40px; font-weight: 700; color: #e63946; margin: 4px 0 20px; }
.tip { color: #909399; font-size: 13px; }
</style>