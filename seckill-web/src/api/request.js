import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

// 统一请求层：dev 代理 /api -> gateway:8080；Sa-Token 用 Authorization: Bearer <token>（各服务 8.13）
const request = axios.create({ baseURL: '/api', timeout: 15000 })

request.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = 'Bearer ' + auth.token
  }
  return config
})

// 错误码统一兜底（错误码表第 11 节：0/401/4001-4009/4290/5000）。HTTP 层 4xx/5xx 同样透传业务码
request.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      return handleBizError(body, resp)
    }
    return body
  },
  (err) => {
    const resp = err.response
    if (resp && resp.data && typeof resp.data.code === 'number') {
      return handleBizError(resp.data, resp)
    }
    ElMessage.error('网络异常，请稍后重试')
    return Promise.reject(err)
  }
)

function handleBizError(body, resp) {
  // 401 未登录：清除本地会话并提示（前端不自动跳转，避免打断浏览；用户可去登录）
  if (body.code === 401) {
    const auth = useAuthStore()
    if (auth.token) auth.logout()
    ElMessage.warning(body.message || '请先登录')
    return Promise.reject(Object.assign(new Error(body.message || 'unauthorized'), { code: body.code }))
  }
  // 4290 限流：秒杀排队中提示
  if (body.code === 4290) {
    ElMessage.warning(body.message || '当前排队人数过多，请稍后重试')
    return Promise.reject(Object.assign(new Error(body.message || 'rate limited'), { code: body.code }))
  }
  // 业务错误（4001-4009/5000 等）直接展示 message，调用方 catch 中可再处理
  ElMessage.error(body.message || '请求失败')
  return Promise.reject(Object.assign(new Error(body.message || 'biz error'), { code: body.code }))
}

export default request