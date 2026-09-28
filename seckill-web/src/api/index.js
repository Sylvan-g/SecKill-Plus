import request from './request'

// 接口契约逐字段对照后端的真实 URL 与 DTO（需求文档 8.x / 源码 controller/DTO）

export const activityApi = {
  // GET /api/goods/activity/list?status=&page=&size=  → {list,total,serverTime}
  list(params) {
    return request.get('/goods/activity/list', { params })
  },
  // GET /api/goods/activity/{id}  → ActivityDetail
  detail(id) {
    return request.get(`/goods/activity/${id}`)
  }
}

export const seckillApi = {
  // POST /api/seckill/do/{activityId}  → {orderNo,status:"QUEUED"}
  doSeckill(activityId) {
    return request.post(`/seckill/do/${activityId}`)
  }
}

export const orderApi = {
  // GET /api/order/status/{orderNo}  → {orderNo,status,price,payDeadline}
  status(orderNo) {
    return request.get(`/order/status/${orderNo}`)
  },
  // GET /api/order/list?status=&page=&size=  → [SeckillOrder]
  list(params) {
    return request.get('/order/list', { params })
  },
  // POST /api/order/pay/{orderNo}  → {orderNo,status,paidAmount,balanceAfter}
  pay(orderNo) {
    return request.post(`/order/pay/${orderNo}`)
  },
  // POST /api/order/cancel/{orderNo}
  cancel(orderNo) {
    return request.post(`/order/cancel/${orderNo}`)
  }
}

export const userApi = {
  register(payload) {
    return request.post('/user/register', payload)
  },
  login(payload) {
    return request.post('/user/login', payload)
  },
  logout() {
    return request.post('/user/logout')
  },
  // GET /api/user/wallet/balance → {userId,balance}
  balance() {
    return request.get('/user/wallet/balance')
  },
  // POST /api/user/wallet/recharge   {amount} → {userId,balance}
  recharge(amount) {
    return request.post('/user/wallet/recharge', { amount })
  }
}

export const adminApi = {
  // POST /api/admin/activity（scheduler 转发 goods）→ activityId
  createActivity(payload) {
    return request.post('/admin/activity', payload)
  },
  // GET /api/admin/log?activityId=&page=&size=（scheduler 转发 order）
  log(params) {
    return request.get('/admin/log', { params })
  }
}