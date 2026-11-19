import http from './http'
import type {
  AccessReport,
  AdminUserRow,
  AppointmentRow,
  ApprovalConfig,
  AuditOptions,
  AuditRow,
  BlacklistEnforceResult,
  BlacklistRow,
  DeptView,
  DeviceCapabilityRow,
  DeviceView,
  DispatchResult,
  EmployeeFace,
  EmployeeView,
  EventMedia,
  EventRow,
  GateOption,
  GateRow,
  GuardDashboard,
  GuardReviewDetail,
  ReviewRow,
  ImportResult,
  JobCodeOption,
  JobRunLogRow,
  LoginResult,
  NotificationRow,
  OnsiteRow,
  PageResult,
  PendingRow,
  QrIssueResult,
  QrVerifyResult,
  ReportDailyResult,
  ReportOverview,
  RoleRow,
  SelfCheckResult,
  SyncBatch,
  SyncBatchItem,
  VisitorReport
} from './types'

/** 响应拦截器已解包 ApiResponse，这里只做类型断言。 */
const get = <T>(url: string, params?: Record<string, unknown>) =>
  http.get(url, { params }) as unknown as Promise<T>
const post = <T>(url: string, data?: unknown, params?: Record<string, unknown>) =>
  http.post(url, data, { params }) as unknown as Promise<T>
const put = <T>(url: string, data?: unknown) => http.put(url, data) as unknown as Promise<T>
const del = <T>(url: string) => http.delete(url) as unknown as Promise<T>

/* ---------------- 认证 ---------------- */

export const login = (username: string, password: string) =>
  post<LoginResult>('/auth/login', { username, password })

/* ---------------- 门点 ---------------- */

export const listGates = () => get<GateRow[]>('/admin/gate')
export const createGate = (data: Record<string, unknown>) => post<GateRow>('/admin/gate', data)
export const updateGate = (id: number, data: Record<string, unknown>) =>
  put<GateRow>(`/admin/gate/${id}`, data)
export const updateGateStatus = (id: number, status: number) =>
  put<GateRow>(`/admin/gate/${id}/status`, { status })

/* ---------------- 设备 ---------------- */

/** 不传 gateId 返回全部门点的设备（多门点部署必须能看到所有门） */
export const listDevices = (gateId?: number) =>
  get<DeviceView[]>('/admin/device', gateId ? { gateId } : undefined)
export const getDevice = (id: number) => get<DeviceView>(`/admin/device/${id}`)

/** 补/改设备档案：名称、型号、出入口方向、所属门点、备注 */
export const updateDevice = (id: number, data: Record<string, unknown>) =>
  put<DeviceView>(`/admin/device/${id}`, data)
export const readDeviceParams = (id: number) => post<Record<string, unknown>>(`/admin/device/${id}/params/read`)
export const writeDeviceParams = (id: number, params: Record<string, unknown>) =>
  post<void>(`/admin/device/${id}/params`, params)
export const openDeviceDoor = (id: number) => post<{ opened: boolean }>(`/admin/device/${id}/door/open`)
export const showDeviceMessage = (id: number, message: string) =>
  post<void>(`/admin/device/${id}/message`, { message })
export const resyncDevice = (id: number) =>
  post<{
    deviceSn: string
    expectedCount: number
    actualCount: number
    missingInDevice: string[]
    extraInDevice: string[]
    removed: number
    failedRemoval: number
    finishedAt: string
  }>(`/admin/device/${id}/resync`)

/** 能力矩阵：换设备/交接时确认这台机器能做什么 */
export const getDeviceCapabilities = (id: number) =>
  get<DeviceCapabilityRow[]>(`/admin/device/${id}/capabilities`)

/** 接入自检：通道、版本、参数、名单逐项 OK/WARN/FAIL */
export const selfCheckDevice = (id: number) =>
  post<SelfCheckResult>(`/admin/device/${id}/selfcheck`)

/* ---------------- 员工 ---------------- */

export const listDepts = () => get<DeptView[]>('/admin/dept')

export const createDept = (data: Record<string, unknown>) =>
  post<DeptView>('/admin/dept', data)

export const updateDept = (id: number, data: Record<string, unknown>) =>
  put<DeptView>(`/admin/dept/${id}`, data)

export const deleteDept = (id: number) => del<void>(`/admin/dept/${id}`)

export const listEmployees = (params: Record<string, unknown>) =>
  get<PageResult<EmployeeView>>('/admin/employee', params)

export const createEmployee = (data: Record<string, unknown>) =>
  post<EmployeeView>('/admin/employee', data)

export const updateEmployee = (id: number, data: Record<string, unknown>) =>
  put<EmployeeView>(`/admin/employee/${id}`, data)

export const stopEmployee = (id: number) => del<void>(`/admin/employee/${id}`)

/** 员工名单下发：入职即自动同步，这里是手动补偿（排查"员工刷不开"时用） */
export const syncEmployee = (id: number) =>
  post<{ employees: number; grants: number; devices: number; success: number; fail: number; message: string }>(
    `/admin/employee/${id}/sync`
  )

/** 一键全量：在岗员工全部下发、停用员工全部撤销 */
export const syncAllEmployees = () =>
  post<{ employees: number; grants: number; devices: number; success: number; fail: number; message: string }>(
    '/admin/employee/sync-all'
  )

export const importEmployees = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/admin/employee/import', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }) as unknown as Promise<ImportResult>
}

export const uploadEmployeeFace = (id: number, file: File) => {
  const form = new FormData()
  form.append('file', file)
  return http.post(`/admin/employee/${id}/face`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }) as unknown as Promise<EmployeeFace>
}

export const listEmployeeFaces = (id: number) =>
  get<EmployeeFace[]>(`/admin/employee/${id}/face/history`)

/* ---------------- 审批配置 ---------------- */

export const getApprovalConfig = () => get<ApprovalConfig>('/admin/approval/config')
export const updateApprovalConfig = (data: Record<string, unknown>) =>
  put<ApprovalConfig>('/admin/approval/config', data)

/* ---------------- 预约 / 授权下发 ---------------- */

export const listAppointments = (params: Record<string, unknown>) =>
  get<PageResult<AppointmentRow>>('/admin/appointment', params)

export const getAppointment = (id: number) => get<AppointmentRow>(`/admin/appointment/${id}`)

export const dispatchAppointment = (appointmentNo: string) =>
  post<DispatchResult>(`/admin/dispatch/${appointmentNo}`)

export const revokeGrant = (appointmentNo: string) =>
  post<DispatchResult>(`/admin/grant/${appointmentNo}/revoke`)

/* ---------------- 黑名单 ---------------- */

export const listBlacklist = (params: Record<string, unknown>) =>
  get<PageResult<BlacklistRow>>('/admin/blacklist', params)

export const createBlacklist = (data: Record<string, unknown>) =>
  post<{ id: number }>('/admin/blacklist', data)

export const updateBlacklistStatus = (id: number, status: number) =>
  put<void>(`/admin/blacklist/${id}/status`, { status })

/** 立即生效（BR-B-003）：撤销在途预约授权、删设备名单、作废通行码 */
export const enforceBlacklist = (id: number) =>
  post<BlacklistEnforceResult>(`/admin/blacklist/${id}/enforce`)

/* ---------------- 动态二维码 ---------------- */

export const issueAppointmentQr = (appointmentNo: string) =>
  post<QrIssueResult>(`/admin/appointment/${appointmentNo}/qr`)

export const currentAppointmentQr = (appointmentNo: string) =>
  get<{ code: string; expireAt: string; status: number } | null>(`/admin/appointment/${appointmentNo}/qr`)

export const revokeAppointmentQr = (appointmentNo: string) =>
  post<{ revoked: number }>(`/admin/appointment/${appointmentNo}/qr/revoke`)

export const issueGuardQr = (appointmentNo: string) =>
  post<QrIssueResult>(`/guard/appointment/${appointmentNo}/qr`)

export const verifyGuardQr = (code: string) => post<QrVerifyResult>('/guard/qr/verify', { code })

/* ---------------- 通行记录 ---------------- */

export const listEvents = (params: Record<string, unknown>) =>
  get<PageResult<EventRow>>('/admin/access/event', params)

export const listEventMedia = (eventId: number) =>
  get<EventMedia[]>(`/admin/access/event/${eventId}/media`)

/* ---------------- 下发批次 ---------------- */

export const listBatches = (params: Record<string, unknown>) =>
  get<PageResult<SyncBatch>>('/admin/sync/batch', params)

export const listBatchItems = (id: number, params: Record<string, unknown>) =>
  get<PageResult<SyncBatchItem>>(`/admin/sync/batch/${id}/items`, params)

/* ---------------- 账号与角色 ---------------- */

export const listRoles = () => get<RoleRow[]>('/admin/role')

export const listAdminUsers = (params: Record<string, unknown>) =>
  get<PageResult<AdminUserRow>>('/admin/user', params)

export const createAdminUser = (data: Record<string, unknown>) =>
  post<{ id: number }>('/admin/user', data)

export const updateAdminUser = (id: number, data: Record<string, unknown>) =>
  put<void>(`/admin/user/${id}`, data)

export const resetUserPassword = (id: number, newPassword: string) =>
  put<void>(`/admin/user/${id}/password`, { newPassword })

export const updateUserStatus = (id: number, status: number) =>
  put<void>(`/admin/user/${id}/status`, { status })

export const changeOwnPassword = (oldPassword: string, newPassword: string) =>
  put<void>('/admin/user/me/password', { oldPassword, newPassword })

/* ---------------- 审计日志 ---------------- */

export const listAudit = (params: Record<string, unknown>) =>
  get<PageResult<AuditRow>>('/admin/audit', params)

export const auditOptions = () => get<AuditOptions>('/admin/audit/options')

/* ---------------- 报表 ---------------- */

export const reportOverview = (params: Record<string, unknown>) =>
  get<ReportOverview>('/admin/report/overview', params)

export const reportDaily = (params: Record<string, unknown>) =>
  get<ReportDailyResult>('/admin/report/daily', params)

export const reportVisitor = (params: Record<string, unknown>) =>
  get<VisitorReport>('/admin/report/visitor', params)

export const reportAccess = (params: Record<string, unknown>) =>
  get<AccessReport>('/admin/report/access', params)

/* ---------------- 运维（定时任务 / 通知） ---------------- */

export const listJobLog = (params: Record<string, unknown>) =>
  get<PageResult<JobRunLogRow>>('/admin/job/log', params)

export const listJobCodes = () => get<JobCodeOption[]>('/admin/job/codes')

export const runJob = (code: string) =>
  post<{ jobCode: string; affected: number }>(`/admin/job/${code}/run`)

export const listNotifications = (params: Record<string, unknown>) =>
  get<PageResult<NotificationRow>>('/admin/notification', params)

/* ---------------- 保安工作台 ---------------- */

export const guardDashboard = () => get<GuardDashboard>('/guard/dashboard')

/** 保安可操作门点（人工开门要先选门；保安无 ADMIN 权限，走 /guard/gate） */
export const listGuardGates = () => get<GateOption[]>('/guard/gate')

/* ---------------- 保安复核（C-65：被访人确认 + 保安复核） ---------------- */

export const guardReviewTodo = (params: Record<string, unknown>) =>
  get<PageResult<ReviewRow>>('/guard/approval/todo', params)

export const guardReviewCount = () => get<{ pending: number }>('/guard/approval/count')

export const guardReviewDetail = (taskId: number) =>
  get<GuardReviewDetail>(`/guard/approval/${taskId}`)

export const guardReviewPass = (taskId: number, data: Record<string, unknown>) =>
  post<{ appointmentNo: string; status: number; statusText: string; dispatchStarted: boolean }>(
    `/guard/approval/${taskId}/pass`,
    data
  )

export const guardReviewReject = (taskId: number, comment: string) =>
  post<void>(`/guard/approval/${taskId}/reject`, { comment })
export const guardOnsite = (params: Record<string, unknown>) =>
  get<PageResult<OnsiteRow>>('/guard/visit/onsite', params)
export const guardPending = (params: Record<string, unknown>) =>
  get<PageResult<PendingRow>>('/guard/appointment/pending', params)
export const guardRemind = (appointmentNo: string) =>
  post<{ remaining: number }>(`/guard/appointment/${appointmentNo}/remind`)
export const guardOpenDoor = (payload: {
  appointmentNo?: string
  employeeId?: number
  gateId?: number
  reason?: string
}) => post<{ opened: boolean; devices: number }>('/guard/door/open', payload)
export const guardCheckout = (appointmentNo: string) =>
  post<void>(`/guard/visit/${appointmentNo}/checkout`)
