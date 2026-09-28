// 时间与状态展示工具：后端 LocalDateTime 序列化为 ISO 格式（如 2026-09-25T12:00:00），前端展示替换 T

export function formatTime(iso) {
  if (!iso) return '-'
  return String(iso).replace('T', ' ')
}

export function countdownText(startTime, endTime, serverTime) {
  const now = serverTime ? new Date(serverTime).getTime() : Date.now()
  const start = new Date(startTime).getTime()
  const end = new Date(endTime).getTime()

  if (now < start) {
    return { status: 'notStarted', label: '距离开抢 ' + remain(now, start) }
  }
  if (now > end) {
    return { status: 'ended', label: '本场已结束' }
  }
  return { status: 'ongoing', label: '抢购进行中 ' + remain(now, end) }
}

function remain(now, target) {
  let diff = Math.floor((target - now) / 1000)
  if (diff < 0) diff = 0
  const h = String(Math.floor(diff / 3600)).padStart(2, '0')
  const m = String(Math.floor((diff % 3600) / 60)).padStart(2, '0')
  const s = String(diff % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
}

// 订单状态文案（需求文档 3.1：0待支付 1已支付 2已取消(超时) 3已取消(主动)）
export const ORDER_STATUS_TEXT = {
  0: '待支付',
  1: '已支付',
  2: '已取消(超时)',
  3: '已取消(主动)'
}

export const ORDER_STATUS_TAG = {
  0: 'warning',
  1: 'success',
  2: 'info',
  3: 'info'
}

// 活动状态文案（0未开始 1进行中 2已结束）
export const ACTIVITY_STATUS_TEXT = { 0: '未开始', 1: '火爆抢购中', 2: '已结束' }
export const ACTIVITY_STATUS_TAG = { 0: 'info', 1: 'danger', 2: '' }