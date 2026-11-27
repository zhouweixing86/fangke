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
  MyNotification,
  OnsiteRow,
  PageResult,
  PendingRow,
  QrIssueResult,
  QrVerifyResult,
  ReportDaily,
  ReportOverview,
  ReviewDetail,
  ReviewRow,
  VisitDictOption,
  BindPreview,
  BindResult,
  ApprovalDetail,
  MyApprovalDone,
  TodoView
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
    /** C-76：已扫码绑定的后台角色编码（有值就会在小程序里显示「我的审批」） */
    roleCodes?: string[]
    approver?: boolean
    bindingId?: number
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

/* ---------------- 微信角色绑定 + 我的审批（C-76） ---------------- */

export const bindPreview = (token: string) =>
  get<BindPreview>('/app/bind/preview', { token })

export const bindRole = (payload: Record<string, unknown>) =>
  post<BindResult>('/app/bind', payload)

export const myApprovalCount = () => get<{ pending: number }>('/app/approval/count')

export const myApprovalTodo = (params: Record<string, unknown>) =>
  get<PageResult<TodoView>>('/app/approval/todo', params)

export const myApprovalDone = (params: Record<string, unknown>) =>
  get<PageResult<MyApprovalDone>>('/app/approval/done', params)

/** 履职统计：我的审批台账汇总 */
export const myApprovalStats = (params: Record<string, unknown> = {}) =>
  get<{ total: number; approved: number; rejected: number }>('/app/approval/stats', params)

export const myApprovalDetail = (taskId: number) =>
  get<ApprovalDetail>('/app/approval/' + taskId)

export const approveTask = (taskId: number, payload: Record<string, unknown>) =>
  post<{ appointmentNo: string; status: number; statusText: string }>(
    '/app/approval/' + taskId + '/pass',
    payload
  )

export const rejectTask = (taskId: number, comment: string) =>
  post<void>('/app/approval/' + taskId + '/reject', { comment })

/** 预约页基础字典：事由 / 区域（C-75） */
export const appDictOptions = () =>
  get<{ CAUSE: VisitDictOption[]; AREA: VisitDictOption[] }>('/app/dict/options')

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

/* ---------------- 管理端审核（单级） ---------------- */

export const guardReviewTodo = (params: Record<string, unknown>) =>
  get<PageResult<ReviewRow>>('/guard/approval/todo', params)

export const guardReviewDetail = (taskId: number) =>
  get<ReviewDetail>(`/guard/approval/${taskId}`)

export const guardReviewPass = (
  taskId: number,
  data: { comment?: string; approvedGateIds?: number[]; qrApproved?: boolean }
) => post<{ appointmentNo: string; status: number; statusText: string; dispatchStarted: boolean }>(
  `/guard/approval/${taskId}/pass`, data)

export const guardReviewReject = (taskId: number, comment: string) =>
  post<void>(`/guard/approval/${taskId}/reject`, { comment })

/* ---------------- 我的消息（审批抄送） ---------------- */

export const listMyNotifications = (params: Record<string, unknown>) =>
  get<PageResult<MyNotification>>('/admin/notification/mine', params)

export const myUnreadCount = () =>
  get<{ unread: number }>('/admin/notification/mine/unread-count')

export const readMyNotification = (id: number) =>
  post<void>(`/admin/notification/mine/${id}/read`)

export const readAllMyNotifications = () =>
  post<{ updated: number }>('/admin/notification/mine/read-all')

/* ---------------- 小程序消息中心（员工/访客，按 token 身份取自己的消息） ---------------- */

export const listAppNotifications = (params: Record<string, unknown>) =>
  get<PageResult<MyNotification>>('/app/notifications', params)

export const appUnreadCount = () => get<{ unread: number }>('/app/notifications/unread-count')

export const readAppNotification = (id: number) => post<void>(`/app/notifications/${id}/read`)

export const readAllAppNotifications = () => post<{ updated: number }>('/app/notifications/read-all')

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
