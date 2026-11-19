import axios from 'axios'
import { ElMessage } from 'element-plus'

export const TOKEN_KEY = 'fk_token'
export const USER_KEY = 'fk_user'

/** 已经用 ElMessage 提示过的错误，业务代码不捕获也不再往控制台抛。 */
export class ApiError extends Error {
  readonly handled = true
}

window.addEventListener('unhandledrejection', (event) => {
  if (event.reason instanceof ApiError) {
    event.preventDefault()
  }
})

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 后端统一响应 { code, message, data }
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new ApiError(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const status = error?.response?.status
    const message = error?.response?.data?.message || error?.message || '网络异常'
    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      if (!location.hash.startsWith('#/login')) {
        location.hash = '#/login'
      }
    }
    ElMessage.error(message)
    return Promise.reject(new ApiError(message))
  }
)

export default http
