import { reactive } from 'vue'

// 用户登录态（token 存 localStorage，与 Sa-Token 会话配套；需求 8.13）
const AUTH_KEY = 'seckill_auth'

function load(key) {
  try {
    return localStorage.getItem(AUTH_KEY + ':' + key)
  } catch {
    return null
  }
}

function save() {
  localStorage.setItem(AUTH_KEY + ':token', auth.token || '')
  localStorage.setItem(AUTH_KEY + ':userId', auth.userId || '')
  localStorage.setItem(AUTH_KEY + ':nickname', auth.nickname || '')
  localStorage.setItem(AUTH_KEY + ':balance', auth.balance != null ? auth.balance : '')
}

const auth = reactive({
  token: load('token'),
  userId: load('userId'),
  nickname: load('nickname'),
  balance: load('balance')
})

function setSession({ token, userId, nickname, balance }) {
  auth.token = token || auth.token
  auth.userId = userId || auth.userId
  auth.nickname = nickname || auth.nickname
  if (balance != null) {
    auth.balance = balance
  }
  save()
}

function setBalance(balance) {
  auth.balance = balance
  save()
}

function logout() {
  auth.token = ''
  auth.userId = ''
  auth.nickname = ''
  auth.balance = ''
  save()
}

// P1-1 修复：方法挂到同一个 reactive 引用、模板保持响应式（此前展开副本 {...auth} 脱离响应，
// 导致登录/充值后头部与余额不随 SPA 内导航刷新）
export function useAuthStore() {
  auth.setSession = setSession
  auth.setBalance = setBalance
  auth.logout = logout
  return auth
}