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
  roleCodes?: string[]
  approver?: boolean
}) {
  const roles = [result.role]
  if (result.approver) roles.push('APP_APPROVER')
  for (const code of result.roleCodes ?? []) {
    if (roles.indexOf(code) < 0) roles.push(code)
  }
  persist(result.token, {
    kind: 'APP',
    name: result.name,
    role: result.role,
    roles,
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
/** C-76：扫码绑定过任意后台角色 → 小程序显示「我的审批」 */
export const isApprover = () => auth.kind === 'APP' && auth.roles.indexOf('APP_APPROVER') >= 0

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
  if (auth.kind === 'APP' && auth.role) return '/pages/visitor/home'
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
const WX_MOCK_KEY = 'fk_wx_mock'

/**
 * 取一次微信登录 code。
 *
 * <p><b>mock 联调</b>：后端把 code 当 openid，而 `wx.login` 每次返回的 code 都不同，
 * 每次都重新取会让 openid 变化，所以 mock 下复用缓存（见 cacheWxCode）。
 *
 * <p><b>真实模式</b>：code 一次性且 5 分钟过期，**绝不能复用**，否则后端会报
 * `invalid code`（40029）。因此只有明确标记过 mock 时才读缓存，其余每次都重新 wx.login。
 */
export function requestWxCode(): Promise<string> {
  const isMock = uni.getStorageSync(WX_MOCK_KEY) === true
  const cached = isMock ? uni.getStorageSync(WX_CODE_KEY) : ''
  if (cached) return Promise.resolve(cached as string)
  return new Promise<string>((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (res: any) => (res.code ? resolve(res.code) : reject(new Error('未获取到 code'))),
      fail: () => reject(new Error('微信登录失败'))
    })
  })
}

/** 后端返回 mock=true 时才记住 code（真实模式的 code 一次性，不能复用）。 */
export function cacheWxCode(code: string, mock = false) {
  if (mock) {
    uni.setStorageSync(WX_CODE_KEY, code)
    uni.setStorageSync(WX_MOCK_KEY, true)
  } else {
    clearWxCode()
  }
}

/** 换真机/换微信时清掉，避免身份串号。 */
export function clearWxCode() {
  uni.removeStorageSync(WX_CODE_KEY)
  uni.removeStorageSync(WX_MOCK_KEY)
}

/**
 * 用一次性 code 调后端；遇到 `invalid code`（40029/40163）自动换新 code 重试一次。
 *
 * <p>真机上 code 只能用一次，超时、重复提交、或旧版本残留的缓存 code 都会导致这个错误，
 * 重试比让用户重进页面体验更好。
 */
export async function withFreshWxCode<T>(
  call: (code: string) => Promise<T>
): Promise<{ result: T; code: string }> {
  let code = await requestWxCode()
  try {
    return { result: await call(code), code }
  } catch (e: any) {
    const message = String(e?.message || '')
    if (!/invalid code|40029|40163/i.test(message)) {
      throw e
    }
    clearWxCode()
    code = await requestWxCode()
    return { result: await call(code), code }
  }
}