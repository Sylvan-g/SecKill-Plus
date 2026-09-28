<template>
  <div class="admin-log">
    <el-card shadow="never">
      <div class="head">
        <h2>运营端 · 秒杀日志</h2>
        <el-form inline>
          <el-form-item label="活动ID">
            <el-input-number v-model="activityId" :min="1" :precision="0" placeholder="全部" clearable />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadList(1)">查询</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="orderNo" label="订单号" width="200" />
        <el-table-column prop="activityId" label="活动ID" width="100" />
        <el-table-column prop="userId" label="用户ID" width="100" />
        <el-table-column prop="action" label="动作" width="160" />
        <el-table-column prop="detail" label="详情" min-width="200" />
        <el-table-column label="时间" width="190">
          <template #default="{ row }">{{ formatTime(row.createdAt || row.createTime) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="loadList"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'
import { formatTime } from '../utils/format'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const activityId = ref(undefined)
const loading = ref(false)

async function loadList(p = 1) {
  page.value = p
  loading.value = true
  try {
    // scheduler 透传 order logs 的 data 分页对象 {list,total}（T11 单层透出 + review P1-3 契约补齐）
    const res = await adminApi.log({ activityId: activityId.value, page: p, size: size.value })
    const data = res.data
    if (data && Array.isArray(data.list)) {
      rows.value = data.list
      total.value = Number(data.total) || data.list.length
    } else {
      rows.value = []
      total.value = 0
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => loadList(1))
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>