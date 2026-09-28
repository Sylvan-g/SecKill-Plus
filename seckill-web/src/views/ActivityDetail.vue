<template>
  <div class="detail">
    <el-card v-loading="loading" shadow="never">
      <el-row :gutter="20" v-if="detail">
        <el-col :span="10">
          <el-image :src="detail.goodsImg || fallbackImg" fit="cover" style="width: 100%; height: 260px" />
        </el-col>
        <el-col :span="14">
          <h2>{{ detail.goodsName }}</h2>
          <p>
            <span class="price">¥{{ detail.seckillPrice }}</span>
            <s class="origin">¥{{ detail.originalPrice }}</s>
          </p>
          <p>剩余库存：<b>{{ detail.stock }}</b> 件（限购 {{ detail.limitPerUser }} 件）</p>
          <p>
            场次：{{ formatTime(detail.startTime) }} ~ {{ formatTime(detail.endTime) }}
          </p>
          <p class="cd" :class="'cd-' + cd.status">{{ cd.label }}</p>
          <div class="actions">
            <el-button type="danger" size="large" :loading="seckilling" :disabled="cd.status !== 'ongoing'"
              @click="doSeckill">
              {{ cd.status === 'ongoing' ? '立即秒杀' : '未开场' }}
            </el-button>
            <el-button v-if="!auth.token" type="primary" size="large" plain @click="$router.push('/login')">
              登录后开抢
            </el-button>
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { activityApi, seckillApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { formatTime, countdownText } from '../utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const detail = ref(null)
const loading = ref(false)
const seckilling = ref(false)
const cd = ref({ status: 'notStarted', label: '加载中' })
const fallbackImg = 'https://console.enterprise.trae.cn/api/ide/v1/text_to_image?prompt=wireless%20earbuds%3A%20premium%20product%20on%20orange%20background%2C%20ecommerce%2C%20photorealistic&image_size=square'

async function load() {
  loading.value = true
  try {
    const res = await activityApi.detail(route.params.id)
    detail.value = res.data
    cd.value = countdownText(detail.value.startTime, detail.value.endTime, detail.value.serverTime)
  } finally {
    loading.value = false
  }
}

async function doSeckill() {
  if (!auth.token) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  seckilling.value = true
  try {
    const res = await seckillApi.doSeckill(detail.value.activityId)
    ElMessage.success('抢购请求已受理，正在处理…')
    router.push({ path: '/seckill/result', query: { orderNo: res.data.orderNo } })
  } catch (e) {
    // 401/4290/4001-4003 已在请求层统一提示
  } finally {
    seckilling.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.price { color: #e63946; font-weight: 700; font-size: 28px; margin-right: 10px; }
.origin { color: #909399; font-size: 14px; }
.cd { margin: 8px 0; }
.cd-notStarted { color: #e6a23c; }
.cd-ongoing { color: #e63946; font-weight: 700; font-size: 18px; }
.cd-ended { color: #909399; }
.actions { margin-top: 20px; }
</style>