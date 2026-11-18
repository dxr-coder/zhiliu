/**
 * Java 业务主干 API（Spring Boot :8081，经 vite /api 代理）
 * 功能：独立 token（java_token），与 Python 会话隔离；401 清 token 踢业务台登录。
 * 数据流向：页面 -> 本实例 -> /api/** -> Java。
 * 数据形状：Result{code,msg,data}，业务错误 code!=200。
 */
import axios from 'axios'

export const javaApi = axios.create({ baseURL: '/api' })

javaApi.interceptors.request.use((config) => {
  const t = localStorage.getItem('java_token')
  if (t) config.headers.Authorization = `Bearer ${t}`
  return config
})

javaApi.interceptors.response.use(
  (res) => {
    // Java 统一 Result：HTTP 200 但 code!=200 视为业务失败
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body && body.code !== 200 && body.code !== undefined) {
      const err = new Error(body.msg || '业务错误')
      err.code = body.code
      err.response = res
      return Promise.reject(err)
    }
    return res
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('java_token')
      localStorage.removeItem('java_user')
      if (window.location.pathname.startsWith('/office')) {
        window.location.href = '/office'
      }
    }
    // 业务 409 等：Java 可能 HTTP200+code409，已在上面处理；HTTP 错误透传
    const body = err.response?.data
    if (body && body.msg) {
      err.message = body.msg
    }
    return Promise.reject(err)
  }
)

export function javaLogin(username, password) {
  return javaApi.post('/auth/login', { username, password }).then((res) => {
    const d = res.data.data
    localStorage.setItem('java_token', d.token)
    localStorage.setItem('java_user', JSON.stringify(d.user))
    return d
  })
}

export function javaLogout() {
  localStorage.removeItem('java_token')
  localStorage.removeItem('java_user')
}

export function javaUser() {
  try {
    return JSON.parse(localStorage.getItem('java_user') || 'null')
  } catch {
    return null
  }
}

export function javaLoggedIn() {
  return !!localStorage.getItem('java_token')
}
