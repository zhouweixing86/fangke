import { API_BASE, del, get, getToken, post, put } from './http'
import { showToast } from '@/utils/ui'
import type {
  AdminLoginResult,
  AppointmentView,
  BlacklistRow,
  DeviceView,
  DispatchResult,
  EventMedia,
  EventRow,
  GateOption,
  GuardDashboard,
  OnsiteRow,
  PageResult,
  PendingRow,
  QrIssueResult,
  QrVerifyResult,
  ReportDaily,
  ReportOverview
} from './types'

/* ---------------- 登录（只有管理端；访客走微信小程序静默登录） ---------------- */

/** 微信小程序一键登录：wx.login 拿到的 code 直接换 token（不用注册/验证码） */
export const wxLogin = (code: string) =>
  post<{
    token: string
    role: string
    subjectId: number
    name: string
    openid: string
    bound: boolean
    /** mock 模式：openid 由 code 派生，前端需复用同一个 code */
    mock: boolean
    expiresInSeconds: number
  }>('/app/auth/wx-login', { code })

/** 被访人手机号 → 自动匹配被访人（访客预约表单） */
export const lookupEmployee = (phone: string) =>
  get<{ matched: boolean; name?: string; title?: string; deptName?: string; message?: string }>(
    '/app/employee/lookup',
    { phone }
  )

export const adminLogin = (username: string, password: string) =>
  post<AdminLoginResult>('/auth/login', { username, password })

/* ---------------- 访客 ---------------- */

export const myAppointments = (params: Record<string, unknown>) =>
  get<PageResult<AppointmentView>>('/app/appointment', params)

export const appointmentDetail = (no: string) => get<AppointmentView>('/app/appointment/' + no)

/** 可选门点（厂区多门点时访客要选从哪个门进） */
export const listAppGates = () => get<GateOption[]>('/app/gate')

export const createAppointment = (payload: Record<string, unknown>) =>
  post<AppointmentView>('/app/appointment', payload)

export const cancelAppointment = (no: string, reason?: string) =>
  post<void>('/app/appointment/' + no + '/cancel', { reason })

export const issueQr = (no: string) => post<QrIssueResult>('/app/appointment/' + no + '/qr')

/* ---------------- 保安工作台 ---------------- */

export const guardDashboard = () => get<GuardDashboard>('/guard/dashboard')
export const guardOnsite = (params: Record<string, unknown>) =>
  get<PageResult<OnsiteRow>>('/guard/visit/onsite', params)
export const guardPending = (params: Record<string, unknown>) =>
  get<PageResult<PendingRow>>('/guard/appointment/pending', params)
export const guardRemind = (no: string) =>
  post<{ remaining: number }>('/guard/appointment/' + no + '/remind')
export const guardOpenDoor = (payload: Record<string, unknown>) =>
  post<{ opened: boolean; devices: number }>('/guard/door/open', payload)
export const guardCheckout = (no: string) => post<void>('/guard/visit/' + no + '/checkout')
export const guardVerifyQr = (code: string) => post<QrVerifyResult>('/guard/qr/verify', { code })

/* ---------------- 管理端 ---------------- */

export const listAppointments = (params: Record<string, unknown>) =>
  get<PageResult<Record<string, any>>>('/admin/appointment', params)

export const adminDispatch = (no: string) =>
  post<DispatchResult>('/admin/dispatch/' + no)

export const revokeGrant = (no: string) =>
  post<DispatchResult>('/admin/grant/' + no + '/revoke')

export const listEvents = (params: Record<string, unknown>) =>
  get<PageResult<EventRow>>('/admin/access/event', params)

export const listEventMedia = (eventId: number) =>
  get<EventMedia[]>('/admin/access/event/' + eventId + '/media')

export const listDevices = () => get<DeviceView[]>('/admin/device')
export const openDeviceDoor = (id: number) => post<{ opened: boolean }>('/admin/device/' + id + '/door/open')

export const listBlacklist = (params: Record<string, unknown>) =>
  get<PageResult<BlacklistRow>>('/admin/blacklist', params)
export const createBlacklist = (data: Record<string, unknown>) =>
  post<{ id: number }>('/admin/blacklist', data)
export const updateBlacklistStatus = (id: number, status: number) =>
  put<void>('/admin/blacklist/' + id + '/status', { status })
export const enforceBlacklist = (id: number) =>
  post<{ visitorId: number | null; appointments: number; cancelled: number; revoked: number; failed: number; message: string }>(
    '/admin/blacklist/' + id + '/enforce'
  )

export const reportOverview = (params: Record<string, unknown>) =>
  get<ReportOverview>('/admin/report/overview', params)
export const reportDaily = (params: Record<string, unknown>) =>
  get<{ from: string; to: string; list: ReportDaily[] }>('/admin/report/daily', params)

/* 供页面在需要时直接调用的低层方法 */
export { del }
