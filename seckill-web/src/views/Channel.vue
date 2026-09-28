<template>
  <div class="channel">
    <el-card shadow="never">
      <div class="channel-head">
        <h2>限时闪购频道</h2>
        <el-radio-group v-model="statusFilter" @change="loadList(1)">
          <el-radio-button :value="null">全部</el-radio-button>
          <el-radio-button :value="0">未开始</el-radio-button>
          <el-radio-button :value="1">进行中</el-radio-button>
          <el-radio-button :value="2">已结束</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="list" v-loading="loading" @row-click="goDetail">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <b>{{ row.goodsName }}</b>
          </template>
        </el-table-column>
        <el-table-column label="秒杀价" width="120">
          <template #default="{ row }">
            <span class="price">¥{{ row.seckillPrice }}</span>
            <s class="origin">¥{{ row.originalPrice }}</s>
          </template>
        </el-table-column>
        <el-table-column label="剩余库存" width="100">
          <template #default="{ row }">{{ row.stock }}</template>
        </el-table-column>
        <el-table-column label="限购" width="80">
          <template #default="{ row }">{{ row.limitPerUser }} 件</template>
        </el-table-column>
        <el-table-column label="场次时间" width="230">
          <template #default="{ row }">
            {{ formatTime(row.startTime) }}<br />{{ formatTime(row.endTime) }}
          </template>
        </el-table-column>
        <el-table-column label="倒计时" min-width="180">
          <template #default="{ row }">
            <span :class="'cd-' + countdown(row).status">{{ countdown(row).label }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="ACTIVITY_STATUS_TAG[row.status]">{{ ACTIVITY_STATUS_TEXT[row.status] }}</el-tag>
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
import { useRouter } from 'vue-router'
import { activityApi } from '../api'
import { formatTime, countdownText, ACTIVITY_STATUS_TEXT, ACTIVITY_STATUS_TAG } from '../utils/format'

const router = useRouter()
const list = ref([])
const total = ref(0)
const page = ref(1)
const statusFilter = ref(null)
const loading = ref(false)

const countdownCache = new Map()
function countdown(row) {
  return countdownCache.get(row) || {}
}

async function loadList(p = 1) {
  page.value = p
  loading.value = true
  try {
    const res = await activityApi.list({ status: statusFilter.value, page: p, size: 10 })
    list.value = res.data.list
    total.value = res.data.total
    const serverTime = res.data.serverTime
    list.value.forEach((row) => countdownCache.set(row, countdownText(row.startTime, row.endTime, serverTime)))
  } finally {
    loading.value = false
  }
}

function goDetail(row) {
  router.push(`/activity/${row.activityId}`)
}

onMounted(() => loadList(1))
</script>

<style scoped>
.channel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.price { color: #e63946; font-weight: 700; font-size: 16px; margin-right: 6px; }
.origin { color: #909399; }
.cd-notStarted { color: #e6a23c; }
.cd-ongoing { color: #e63946; font-weight: 700; }
.cd-ended { color: #909399; }
.pager { margin-top: 16px; justify-content: flex-end; }
.el-card :deep(.el-table__row) { cursor: pointer; }
</style>