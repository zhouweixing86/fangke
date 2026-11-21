import { reactive } from 'vue'
import { clearAuth, getStoredUser, getToken, setStoredUser, setToken } from '@/api/http'
import type { AdminLoginResult } from '@/api/types'

/** 登录身份种类：APP=访客/员工（手机号验证码），ADMIN=后台账号（账号密码） */
export type AuthKind = 'APP' | 'ADMIN'

interface StoredUser {
  kind: AuthKind
  name: string
  role: string
  roles: string[]
  subjectId: number
  phoneMask?: string
  /** 小程序 openid：首次预约时传给后端绑定，之后「我的预约」才能查到自己的单 */
  openid?: string
}

interface AuthState extends StoredUser {
  token: string
}

function restore(): AuthState {
  const raw = getStoredUser<StoredUser>()
  return {
    token: getToken(),
    kind: raw?.kind ?? 'APP',
    name: raw?.name ?? '',
    role: raw?.role ?? '',
    roles: raw?.roles ?? [],
    subjectId: raw?.subjectId ?? 0,
    phoneMask: raw?.phoneMask ?? '',
    openid: raw?.openid ?? ''
  }
}

export const auth = reactive<AuthState>(restore())

function persist(token: string, user: StoredUser) {
  setToken(token)
  setStoredUser(user)
  auth.token = token
  auth.kind = user.kind
  auth.name = user.name
  auth.role = user.role
  auth.roles = user.roles
  auth.subjectId = user.subjectId
  auth.phoneMask = user.phoneMask ?? ''
  auth.openid = user.openid ?? ''
}

export function applyWxLogin(result: {
  token: string
  role: string
  subjectId: number
  name: string
  openid?: string
}) {
  persist(result.token, {
    kind: 'APP',
    name: result.name,
    role: result.role,
    roles: [result.role],
    subjectId: result.subjectId,
    phoneMask: '',
    openid: result.openid
  })
}

export function applyAdminLogin(result: AdminLoginResult) {
  const roles = result.roles ?? []
  persist(result.token, {
    kind: 'ADMIN',
    name: result.displayName || result.username,
    role: roles[0] ?? '',
    roles,
    subjectId: 0
  })
}

export function logout() {
  clearAuth()
  auth.token = ''
  auth.kind = 'APP'
  auth.name = ''
  auth.role = ''
  auth.roles = []
  auth.subjectId = 0
  auth.phoneMask = ''
  uni.reLaunch({ url: '/pages/login/login' })
}

/** 访客端（仅微信小程序使用） */
export const isAppUser = () => auth.kind === 'APP' && !!auth.role
export const isVisitor = () => auth.role === 'APP_VISITOR'

/** 管理端 */
export const isAdminUser = () => auth.kind === 'ADMIN'
export const isAdmin = () => auth.roles.indexOf('ADMIN') >= 0
export const isGuardOrAdmin = () => auth.roles.indexOf('GUARD') >= 0 || isAdmin()

/**
 * 登录后落到哪个首页。
 *
 * <p>只有两个入口：管理端 → 工作台；访客（小程序）→ 首页。
 * 员工不登录，所以没有员工首页。
 */
export function homePath(): string {
  if (isAdminUser()) return '/pages/admin/workbench'
  if (isVisitor()) return '/pages/visitor/home'
  return '/pages/login/login'
}

/** 页面守卫：未登录/角色不符时跳走 */
export function requireLogin(kind: AuthKind = 'APP') {
  if (!auth.token) {
    uni.reLaunch({ url: '/pages/login/login' })
    return false
  }
  if (auth.kind !== kind) {
    uni.reLaunch({ url: homePath() })
    return false
  }
  return true
}

/* ---------------- 微信登录辅助 ---------------- */

const WX_CODE_KEY = 'fk_wx_code'

/**
 * 取一次微信登录 code，优先复用缓存。
 *
 * <p>mock 联调时后端把 code 当 openid，而 `wx.login` 每次返回的 code 都不同——
 * 每次都重新取会让 openid 变化，导致"首次预约绑定"失效、「我的预约」查不到自己的单。
 * 因此缓存复用；真实模式下 code 是一次性的，不能复用，
 * 所以只在后端返回 `mock=true` 时才写缓存（见 cacheWxCode 的调用方）。
 */
export function requestWxCode(): Promise<string> {
  const cached = uni.getStorageSync(WX_CODE_KEY)
  if (cached) return Promise.resolve(cached as string)
  return new Promise<string>((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (res: any) => (res.code ? resolve(res.code) : reject(new Error('未获取到 code'))),
      fail: () => reject(new Error('微信登录失败'))
    })
  })
}

/** 仅 mock 模式调用：记住 code，让后续登录拿到同一个 openid。 */
export function cacheWxCode(code: string) {
  uni.setStorageSync(WX_CODE_KEY, code)
}

/** 换真机/换微信时清掉，避免身份串号。 */
export function clearWxCode() {
  uni.removeStorageSync(WX_CODE_KEY)
}