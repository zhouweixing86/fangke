import { showToast } from '@/utils/ui'

/** H5 默认走 /api 代理；小程序/APP 构建时用 VITE_API_BASE 指定后端绝对地址。 */
const BASE = import.meta.env.VITE_API_BASE || ''

/** 供 uni.uploadFile 等原生上传使用（axios 实例之外） */
export const API_BASE = BASE

const TOKEN_KEY = 'fk_token'
const USER_KEY = 'fk_user'

export function getToken(): string {
  return uni.getStorageSync(TOKEN_KEY) || ''
}

export function setToken(token: string) {
  uni.setStorageSync(TOKEN_KEY, token)
}

export function getStoredUser<T>(): T | null {
  const raw = uni.getStorageSync(USER_KEY)
  if (!raw) return null
  try {
    return typeof raw === 'string' ? (JSON.parse(raw) as T) : (raw as T)
  } catch {
    return null
  }
}

export function setStoredUser(user: unknown) {
  uni.setStorageSync(USER_KEY, JSON.stringify(user))
}

export function clearAuth() {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(USER_KEY)
}

function buildUrl(path: string): string {
  if (path.startsWith('http')) return path
  return BASE + (path.startsWith('/api') ? path : '/api' + path)
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

export function request<T>(method: Method, path: string, data?: unknown): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const header: Record<string, string> = { 'Content-Type': 'application/json' }
    const token = getToken()
    if (token) header.Authorization = 'Bearer ' + token

    uni.request({
      url: buildUrl(path),
      method,
      data: data as any,
      header,
      success: (res) => {
        const status = res.statusCode
        if (status === 401) {
          clearAuth()
          uni.reLaunch({ url: '/pages/login/login' })
          reject(new Error('登录已过期'))
          return
        }
        const body = res.data as any
        if (body && typeof body === 'object' && 'code' in body) {
          if (body.code === 0) {
            resolve(body.data as T)
          } else {
            showToast(body.message || '请求失败')
            reject(new Error(body.message || '请求失败'))
          }
          return
        }
        if (status >= 200 && status < 300) {
          resolve(body as T)
        } else {
          showToast('请求失败(' + status + ')')
          reject(new Error('HTTP ' + status))
        }
      },
      fail: (err) => {
        showToast('网络异常，请检查后端地址')
        reject(new Error(err.errMsg))
      }
    })
  })
}

export const get = <T>(path: string, params?: Record<string, unknown>) => {
  const query = params
    ? '?' +
      Object.entries(params)
        .filter(([, v]) => v !== undefined && v !== null && v !== '')
        .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(String(v)))
        .join('&')
    : ''
  return request<T>('GET', path + (query === '?' ? '' : query))
}
export const post = <T>(path: string, data?: unknown) => request<T>('POST', path, data)
export const put = <T>(path: string, data?: unknown) => request<T>('PUT', path, data)
export const del = <T>(path: string) => request<T>('DELETE', path)
