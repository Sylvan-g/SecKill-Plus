<template>
  <div class="orders">
    <el-card shadow="never">
      <div class="orders-head">
        <h2>我的订单</h2>
        <el-radio-group v-model="statusFilter" @change="loadList(1)">
          <el-radio-button :value="null">全部</el-radio-button>
          <el-radio-button :value="0">待支付</el-radio-button>
          <el-radio-button :value="1">已支付</el-radio-button>
          <el-radio-button :value="2">已取消(超时)</el-radio-button>
          <el-radio-button :value="3">已取消(主动)</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column label="订单号" width="190">
          <template #default="{ row }">{{ row.orderNo }}</template>
        </el-table-column>
        <el-table-column label="商品" min-width="160">
          <template #default="{ row }">{{ row.goodsName }}</template>
        </el-table-column>
        <el-table-column label="金额" width="100">
          <template #default="{ row }">¥{{ row.price }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="支付截止" width="180">
          <template #default="{ row }">{{ formatTime(row.payDeadline) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="ORDER_STATUS_TAG[row.status]">{{ ORDER_STATUS_TEXT[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" size="small" type="danger" @click="pay(row)">去支付</el-button>
            <el-button v-if="row.status === 0" size="small" @click="cancel(row)">取消</el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="10"
        :current-page="page"
        @current-change="loadList"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { orderApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { formatTime, ORDER_STATUS_TEXT, ORDER_STATUS_TAG } from '../utils/format'

const auth = useAuthStore()
const list = ref([])
const total = ref(0)
const page = ref(1)
const statusFilter = ref(null)
const loading = ref(false)

async function loadList(p = 1) {
  page.value = p
  loading.value = true
  try {
    const res = await orderApi.list({ status: statusFilter.value, page: p, size: 10 })
    list.value = res.data
    total.value = res.data.length // 后端返回数组无 total，分页暂用当前页长度兜底
  } finally {
    loading.value = false
  }
}

async function pay(row) {
  try {
    const res = await orderApi.pay(row.orderNo)
    ElMessage.success('支付成功')
    auth.setBalance(res.data.balanceAfter)
    loadList(page.value)
  } catch (e) {
    // 4004-4007 已在请求层提示
    loadList(page.value)
  }
}

async function cancel(row) {
  try {
    await orderApi.cancel(row.orderNo)
    ElMessage.success('订单已取消')
    loadList(page.value)
  } catch (e) {
    // 已在请求层提示
  }
}

onMounted(() => loadList(1))
</script>

<style scoped>
.orders-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.pager { margin-top: 16px; justify-content: flex-end; }
.muted { color: #c0c4cc; }
</style>