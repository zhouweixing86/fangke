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
  SyncBatch,
  SyncBatchItem,
  VisitorReport,
  TrafficDetail,
  TrafficRow,
  VehicleEventRow
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

/** 远程开门：指令随设备下一次心跳下发（通常 30 秒内） */
export const openDeviceDoor = (id: number) => post<{ opened: boolean }>(`/admin/device/${id}/door/open`)

/** 能力矩阵：换设备/交接时确认这台机器能做什么 */
export const getDeviceCapabilities = (id: number) =>
  get<DeviceCapabilityRow[]>(`/admin/device/${id}/capabilities`)

/* ---------------- 设备远程管理（平台直连设备 9090） ---------------- */

export interface DeviceRemoteInfo {
  deviceId: number
  deviceSn: string
  ip?: string
  localAdminUrl: string
  reachable: boolean
  unreachableReason?: string
  version?: Record<string, unknown>
}

export const deviceRemoteInfo = (id: number) =>
  get<DeviceRemoteInfo>(`/admin/device/${id}/remote/info`)
export const deviceRemoteParameters = (id: number) =>
  get<Record<string, unknown>>(`/admin/device/${id}/remote/parameters`)
export const deviceRemoteTime = (id: number) =>
  get<{ deviceTime?: string; platformTime?: string }>(`/admin/device/${id}/remote/time`)
/** 直连开闸：秒级生效，要求平台与设备同网 */
export const deviceRemoteOpen = (id: number) =>
  post<Record<string, unknown>>(`/admin/device/${id}/remote/open`)
export const deviceRemoteSyncTime = (id: number) =>
  post<Record<string, unknown>>(`/admin/device/${id}/remote/sync-time`)
export const deviceRemoteTip = (id: number, data: { msg: string; color?: number; playId?: number }) =>
  post<Record<string, unknown>>(`/admin/device/${id}/remote/tip`, data)
export const deviceRemoteSnapshot = (id: number) =>
  post<{ image?: string }>(`/admin/device/${id}/remote/snapshot`)
export const deviceRemoteGateHold = (id: number, data: { enabled: boolean; minutes?: number }) =>
  post<Record<string, unknown>>(`/admin/device/${id}/remote/gate-hold`, data)
export const deviceRemoteReboot = (id: number) =>
  post<Record<string, unknown>>(`/admin/device/${id}/remote/reboot`)
/** 允许远程切换的安全开关（白名单） */
export const deviceRemoteSwitches = (id: number) =>
  get<Record<string, string>>(`/admin/device/${id}/remote/switches`)
export const deviceRemoteSetSwitches = (id: number, data: Record<string, boolean>) =>
  post<Record<string, unknown>>(`/admin/device/${id}/remote/switches`, data)
/** 二维码核验接线：把设备 thirdQrUrl 指到平台（扫码后回来问平台） */
export const deviceRemoteApplyQrConfig = (id: number) =>
  post<{ qrPushUrl: string; appliedNow: boolean; appliedNowError?: string; queuedDevices: number }>(
    `/admin/device/${id}/remote/qr-config`
  )
export const deviceRemoteRestore = (id: number) =>
  post<Record<string, unknown>>(`/admin/device/${id}/remote/restore`)
/** 从设备本地名单删掉一条（清理孤儿名单/换人时用） */
export const deviceRemoteDeletePerson = (id: number, data: { idno: string; usertype?: string }) =>
  post<Record<string, unknown>>(`/admin/device/${id}/remote/person/delete`, data)

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

/** 恢复在职：状态改回在职并把授权/名单重新下发（等价于重新新增一遍） */
export const enableEmployee = (id: number) => post<EmployeeView>(`/admin/employee/${id}/enable`)

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

/* ---------------- 预约审核（单级：管理端） ---------------- */

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

/* ---------------- 车辆记录（C-68） ---------------- */

export const listVehicleEvents = (params: Record<string, unknown>) =>
  get<PageResult<VehicleEventRow>>('/admin/vehicle/event', params)

/* ---------------- 通行记录（C-69：行人 + 车辆合并） ---------------- */

export const trafficSummary = (params: Record<string, unknown>) =>
  get<PageResult<TrafficRow>>('/admin/traffic/summary', params)

export const trafficDetail = (personNo: string, date: string) =>
  get<TrafficDetail>(`/admin/traffic/${encodeURIComponent(personNo)}/detail`, { date })