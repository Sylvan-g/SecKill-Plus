<template>
  <div class="seckill-result">
    <el-card shadow="never" class="status-card">
      <template v-if="queued">
        <el-result icon="warning" title="正在处理您的秒杀请求" sub-title="正在确认库存与订单状态，请稍候…">
          <template #extra>
            <el-button type="primary" :loading="true">排队中</el-button>
          </template>
        </el-result>
      </template>

      <template v-else-if="status === 'WAIT_PAY'">
        <el-result icon="success" title="恭喜！抢购成功" :sub-title="`订单号：${orderNo}`">
          <template #extra>
            <div class="pay-box">
              <p>应付金额：<b class="price">¥{{ price }}</b></p>
              <p class="deadline">请在 <b>{{ formatTime(payDeadline) }}</b> 前完成支付，逾期自动取消</p>
              <el-button type="danger" size="large" :loading="paying" @click="pay">立即支付</el-button>
              <el-button size="large" @click="cancel">取消订单</el-button>
            </div>
          </template>
        </el-result>
      </template>

      <template v-else-if="status === 'PAID'">
        <el-result icon="success" title="支付成功" sub-title="可在「我的订单」中查看">
          <template #extra>
            <el-button type="primary" @click="$router.push('/orders')">查看订单</el-button>
          </template>
        </el-result>
      </template>

      <template v-else>
        <el-result icon="error" title="订单处理失败" sub-title="可稍后再试或查看我的订单">
          <template #extra>
            <el-button @click="$router.push('/')">返回频道</el-button>
          </template>
        </el-result>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { formatTime } from '../utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const orderNo = route.query.orderNo || ''
const status = ref('')
const price = ref(null)
const payDeadline = ref(null)
const queued = ref(true)
const paying = ref(false)

let timer = null
let seq = 0 // P1-2：轮询请求序号，用于丢弃在途的过期响应（竞态修复）
let consecutiveErrors = 0
let pollCount = 0
const MAX_POLLS = 30 // 最长轮询 60s（2s/次），防 MQ 长期不可用时无限打请求
const MAX_ERRORS = 3 // 连续失败 3 次才进入失败终态，网络抖动不误判订单失败

async function poll() {
  const mySeq = ++seq
  try {
    const res = await orderApi.status(orderNo)
    if (mySeq !== seq) return false // 已有更新的轮询/停止动作，丢弃该过期响应
    consecutiveErrors = 0
    status.value = res.data.status
    price.value = res.data.price
    payDeadline.value = res.data.payDeadline
    if (status.value === 'QUEUED') {
      queued.value = true
      return true // 继续轮询
    }
    queued.value = false
    return false
  } catch (e) {
    if (mySeq !== seq) return false
    // 错误不立即终态：401/4290/网络异常都不是「订单失败」，
    // 连续 MAX_ERRORS 次才降级提示（此时大概率服务侧真有问题）
    consecutiveErrors++
    if (consecutiveErrors >= MAX_ERRORS) {
      queued.value = false
      return false
    }
    return true
  }
}

async function pay() {
  paying.value = true
  try {
    const res = await orderApi.pay(orderNo)
    // 支付成功不依赖 body.status，直接按成功处理（订单最终状态查询为准）
    ElMessage.success('支付成功')
    auth.setBalance(res.data.balanceAfter)
    status.value = 'PAID'
    seq++ // 使所有在途 poll 失效，防止支付后旧响应把页面打回"排队中"
    stop()
  } catch (e) {
    // 4004/4005/4006/4007 已在请求层提示
  } finally {
    paying.value = false
  }
}

async function cancel() {
  try {
    await orderApi.cancel(orderNo)
    ElMessage.success('订单已取消')
    seq++
    stop()
    router.push('/orders')
  } catch (e) {
    // 已在请求层提示
  }
}

function start() {
  poll()
  timer = setInterval(async () => {
    pollCount++
    if (pollCount > MAX_POLLS) {
      seq++
      stop()
      queued.value = false
      return
    }
    const keep = await poll()
    if (!keep) stop()
  }, 2000)
}

function stop() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(start)
onBeforeUnmount(stop)
</script>

<style scoped>
.status-card { max-width: 640px; margin: 40px auto; }
.pay-box { text-align: center; }
.pay-box .price { color: #e63946; font-size: 24px; }
.deadline { color: #909399; margin: 4px 0 16px; }
</style>