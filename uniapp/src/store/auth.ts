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
    phoneMask: raw?.phoneMask ?? ''
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
}

export function applyWxLogin(result: { token: string; role: string; subjectId: number; name: string }) {
  persist(result.token, {
    kind: 'APP',
    name: result.name,
    role: result.role,
    roles: [result.role],
    subjectId: result.subjectId,
    phoneMask: ''
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
