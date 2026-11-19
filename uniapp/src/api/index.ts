import { API_BASE, del, get, getToken, post, put } from './http'
import { showToast } from '@/utils/ui'
import type {
  AdminLoginResult,
  AppLoginResult,
  AppMe,
  AppointmentView,
  ApprovalDetail,
  BlacklistRow,
  DeviceView,
  DispatchResult,
  EventMedia,
  EventRow,
  GateOption,
  GuardDashboard,
  NotificationItem,
  OnsiteRow,
  PageResult,
  PassResult,
  PendingRow,
  QrIssueResult,
  QrVerifyResult,
  ReportDaily,
  ReportOverview,
  SelfCheckResult,
  TodoView
} from './types'

/* ---------------- 登录 ---------------- */

export const appSendCode = (phone: string) =>
  post<{ phoneMask: string; ttlSeconds: number; resendAfterSeconds: number; mockCode: string | null }>(
    '/app/auth/code',
    { phone }
  )

export const appLogin = (phone: string, code: string) =>
  post<AppLoginResult>('/app/auth/login', { phone, code })

export const appMe = () => get<AppMe>('/app/me')

/**
 * 访客自助上传人脸（采集后才能刷脸进门）。
 * 走 uni.uploadFile 而不是 axios，避免三端对 Blob/FormData 支持不一致。
 */
export const uploadVisitorFace = (filePath: string) =>
  new Promise<{ visitorId: number; faceStatus: number; devices: number; success: number; fail: number }>(
    (resolve, reject) => {
      uni.uploadFile({
        url: API_BASE + '/api/app/me/face',
        filePath,
        name: 'file',
        header: { Authorization: 'Bearer ' + getToken() },
        success: (res) => {
          try {
            const body = JSON.parse(res.data as string)
            if (body.code === 0) {
              resolve(body.data)
            } else {
              showToast(body.message || '上传失败')
              reject(new Error(body.message || '上传失败'))
            }
          } catch (e) {
            reject(e)
          }
        },
        fail: (e) => {
          showToast('上传失败，请检查网络')
          reject(e)
        }
      })
    }
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

/* ---------------- 员工审批 ---------------- */

export const myTodo = (params: Record<string, unknown>) =>
  get<PageResult<TodoView>>('/app/approval/todo', params)

export const approvalDetail = (taskId: number) => get<ApprovalDetail>('/app/approval/' + taskId)

export const approvalPass = (taskId: number, comment?: string, approvedGateIds?: number[]) =>
  post<PassResult>('/app/approval/' + taskId + '/pass', { comment, approvedGateIds })

export const approvalReject = (taskId: number, comment: string) =>
  post<void>('/app/approval/' + taskId + '/reject', { comment })

/* ---------------- 消息 ---------------- */

export const myNotifications = (params: Record<string, unknown>) =>
  get<PageResult<NotificationItem>>('/app/notifications', params)

export const unreadCount = () => get<{ unread: number }>('/app/notifications/unread-count')

export const readNotification = (id: number) => post<void>('/app/notifications/' + id + '/read')

export const readAllNotifications = () => post<{ updated: number }>('/app/notifications/read-all')

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
export const selfCheckDevice = (id: number) => post<SelfCheckResult>('/admin/device/' + id + '/selfcheck')
export const openDeviceDoor = (id: number) => post<{ opened: boolean }>('/admin/device/' + id + '/door/open')
export const showDeviceMessage = (id: number, message: string) =>
  post<void>('/admin/device/' + id + '/message', { message })

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
