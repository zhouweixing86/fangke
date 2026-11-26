import http from './http'
import type {
  AccessReport,
  AdminUserRow,
  AppointmentRow,
  ApprovalConfig,
  ApprovalTemplateView,
  RoleOption,
  MyNotification,
  VisitDict,
  VisitDictType,
  VisitRegisterRow,
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
  PermissionItem,
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
  VehicleEventRow,
  VehiclePlateSnapshot,
  WxRoleQr,
  WxBindingRow,
  ApprovalRecordRow,
  VehiclePlateSyncResult,
  VehiclePlatePushState,
  VehicleMqttStatus,
  VehicleMqttLogRow
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
export const listDevices = (gateId?: number, channel?: string) =>
  get<DeviceView[]>('/admin/device', {
    ...(gateId ? { gateId } : {}),
    ...(channel ? { channel } : {})
  })
export const getDevice = (id: number) => get<DeviceView>(`/admin/device/${id}`)

/** 补/改设备档案：名称、型号、出入口方向、所属门点、备注 */
export const updateDevice = (id: number, data: Record<string, unknown>) =>
  put<DeviceView>(`/admin/device/${id}`, data)

/** 删除设备档案（登记错了/换下来的旧机器；最近 5 分钟有心跳的会被拒绝） */
export const deleteDevice = (id: number) => del<{ deleted: boolean }>(`/admin/device/${id}`)

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

/* ---------------- 设备心跳下发（云端可用，延迟一个心跳周期） ---------------- */

export interface DeviceCommandRow {
  id: number
  cmdType: string
  cmdText: string
  status: number
  statusText: string
  retryCount: number
  errorMsg: string
  createdAt: string
  finishedAt: string
}

export interface DeviceCommandStatus {
  deviceId: number
  deviceSn: string
  online: boolean
  lastHeartbeat: string
  heartSeconds: number
  pending: number
  /** 平台期望的设备开关（0/1），不是从设备读回的实时值 */
  overrides: Record<string, number>
  allowedParams: string[]
  /** 平台下发给设备的二维码核验地址（为空表示没配公网地址） */
  qrPushUrl?: string
  commands: DeviceCommandRow[]
}

export interface DeviceCommandResult {
  deviceSn: string
  cmdType: string
  cmdText: string
  queued: boolean
  pending: number
  queuedAt: string
  hint: string
  overrides?: Record<string, number>
  qrPushUrl?: string
  thirdOpenDoorLocal?: number
}

/** 心跳下发总览：在线、待执行数、平台期望参数、最近指令结果 */
export const deviceCmdStatus = (id: number) => get<DeviceCommandStatus>(`/admin/device/${id}/cmd`)
export const deviceCmdOpen = (id: number) => post<DeviceCommandResult>(`/admin/device/${id}/cmd/open`)
export const deviceCmdSyncTime = (id: number) =>
  post<DeviceCommandResult>(`/admin/device/${id}/cmd/sync-time`)
export const deviceCmdSyncParams = (id: number) =>
  post<DeviceCommandResult>(`/admin/device/${id}/cmd/sync-params`)
/** 二维码核验接线：参数下发里带上 thirdQrUrl（云端也能用） */
export const deviceCmdQrConfig = (id: number) =>
  post<DeviceCommandResult>(`/admin/device/${id}/cmd/qr-config`)
/** 改安全开关：存平台期望值 + 排队一次参数下发 */
export const deviceCmdSetParams = (id: number, data: Record<string, boolean>) =>
  post<DeviceCommandResult>(`/admin/device/${id}/cmd/params`, data)

/* ---------------- 车闸 MQTT 通道（docs/22） ---------------- */

export const vehicleMqttStatus = () => get<VehicleMqttStatus>('/admin/vehicle/mqtt')
export const vehicleMqttReconnect = () => post<VehicleMqttStatus>('/admin/vehicle/mqtt/reconnect')
export const vehicleMqttLogs = (limit = 50) =>
  get<VehicleMqttLogRow[]>('/admin/vehicle/mqtt/logs', { limit })
export const vehicleMqttClearLogs = () => del<{ cleared: boolean }>('/admin/vehicle/mqtt/logs')
/** 下发/更新车牌到相机（相机识别到就在白名单里 → 开门） */
export const vehicleMqttAddPlate = (data: {
  deviceSn: string
  plateNo: string
  beginTime?: string
  endTime?: string
}) => post<Record<string, unknown>>('/admin/vehicle/mqtt/plate', data)
/** 从相机删除车牌 */
export const vehicleMqttDeletePlate = (deviceSn: string, plateNo: string) =>
  del<Record<string, unknown>>(
    `/admin/vehicle/mqtt/plate?deviceSn=${encodeURIComponent(deviceSn)}&plateNo=${encodeURIComponent(plateNo)}`
  )
/** 发原始报文（协议探测/联调） */
export const vehicleMqttRaw = (data: { deviceSn: string; payload: string }) =>
  post<Record<string, unknown>>('/admin/vehicle/mqtt/raw', data)
/** 使能下行回执：之后每条下行相机都会回 errCode，便于确认下发是否被接受 */
export const vehicleMqttEnableReply = (deviceSn: string) =>
  post<Record<string, unknown>>(
    `/admin/vehicle/mqtt/enable-reply?deviceSn=${encodeURIComponent(deviceSn)}`
  )
/** 人工远程开闸（车闸相机，走 MQTT）；带车牌会记进该人的通行记录 */
export const vehicleMqttOpen = (deviceSn: string, plateNo?: string) =>
  post<Record<string, unknown>>('/admin/vehicle/mqtt/open', { deviceSn, plateNo })
/** 立即对齐某台相机车牌（不等定时对账） */
export const vehicleMqttSync = (deviceSn: string) =>
  post<Record<string, unknown>>(
    `/admin/vehicle/mqtt/sync?deviceSn=${encodeURIComponent(deviceSn)}`
  )
/** 强制同步：让相机清空本地名单与放行规则后按平台数据全量重推（纠正相机上被人工导入的名单） */
export const vehicleMqttForceResync = (deviceSn: string) =>
  post<Record<string, unknown>>(
    `/admin/vehicle/mqtt/force-resync?deviceSn=${encodeURIComponent(deviceSn)}`
  )
/** 全量对齐：把平台当前车牌推给所有车闸相机 */
export const vehicleMqttImport = (gateId?: number) =>
  post<{ plateTotal: number; devices: unknown[] }>('/admin/vehicle/mqtt/import', undefined, gateId ? { gateId } : undefined)

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

/* ---------------- 审批模板（多级 + 抄送，V24） ---------------- */

export const listApprovalTemplates = () =>
  get<ApprovalTemplateView[]>('/admin/approval/template')
export const approvalRoles = () => get<RoleOption[]>('/admin/approval/template/roles')
export const createApprovalTemplate = (data: Record<string, unknown>) =>
  post<ApprovalTemplateView>('/admin/approval/template', data)
export const updateApprovalTemplate = (id: number, data: Record<string, unknown>) =>
  put<ApprovalTemplateView>(`/admin/approval/template/${id}`, data)
export const toggleApprovalTemplate = (id: number, enabled: boolean) =>
  put<ApprovalTemplateView>(`/admin/approval/template/${id}/enabled`, { enabled })
export const deleteApprovalTemplate = (id: number) =>
  del<void>(`/admin/approval/template/${id}`)

/* ---------------- 我的消息（管理端消息中心） ---------------- */

export const listMyNotifications = (params: Record<string, unknown>) =>
  get<PageResult<MyNotification>>('/admin/notification/mine', params)
export const myUnreadCount = () =>
  get<{ unread: number }>('/admin/notification/mine/unread-count')
export const readMyNotification = (id: number) =>
  post<void>(`/admin/notification/mine/${id}/read`)
export const readAllMyNotifications = () =>
  post<{ updated: number }>('/admin/notification/mine/read-all')

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

export const listRoles = () => get<RoleRow[]>('/admin/roles')

/* ---------------- 角色与权限（C-73） ---------------- */

/** 可分配的页面权限清单 */
export const rolePermissions = () => get<PermissionItem[]>('/admin/roles/permissions')
export const createRole = (data: Record<string, unknown>) =>
  post<{ id: number }>('/admin/roles', data)
export const updateRole = (id: number, data: Record<string, unknown>) =>
  put<void>(`/admin/roles/${id}`, data)
export const deleteRole = (id: number) => del<void>(`/admin/roles/${id}`)

/* ---------------- 微信角色二维码（C-76） ---------------- */

export const listWxRoleQr = () => get<WxRoleQr[]>('/admin/wx-role-qr')
export const regenerateWxRoleQr = (roleId: number) =>
  post<WxRoleQr>(`/admin/wx-role-qr/${roleId}/regenerate`)
/** 小程序码（C-81）：未配置 AppID 时 configured=false，前端回退链接二维码 */
export const fetchWxRoleWxacode = (roleId: number) =>
  get<{ configured: boolean; imageBase64: string | null; hint: string | null }>(
    `/admin/wx-role-qr/${roleId}/wxacode`
  )

/* ---------------- 审批台账（C-80） ---------------- */

export const listApprovalRecords = (params: Record<string, unknown>) =>
  get<PageResult<ApprovalRecordRow>>('/admin/approval/records', params)
export const exportApprovalRecords = (params: Record<string, unknown>) =>
  http.get('/admin/approval/records/export', { params, responseType: 'blob' }) as unknown as Promise<Blob>

/* ---------------- 微信身份（绑定）管理（C-77） ---------------- */

export const listWxBindings = (keyword?: string) =>
  get<WxBindingRow[]>('/admin/wx-bindings', { keyword })
export const revokeWxRole = (bindingId: number, roleCode: string) =>
  del<void>(`/admin/wx-bindings/${bindingId}/roles/${roleCode}`)
export const revokeWxBinding = (bindingId: number) =>
  del<void>(`/admin/wx-bindings/${bindingId}`)

/* ---------------- 来访登记 + 基础字典（V26） ---------------- */

export const visitDictOptions = () =>
  get<Record<VisitDictType, VisitDict[]>>('/admin/visit/dict/options')
export const listVisitDict = (type: VisitDictType) =>
  get<VisitDict[]>('/admin/visit/dict', { type })
export const createVisitDict = (data: Record<string, unknown>) =>
  post<{ id: number }>('/admin/visit/dict', data)
export const updateVisitDict = (id: number, data: Record<string, unknown>) =>
  put<void>(`/admin/visit/dict/${id}`, data)
export const deleteVisitDict = (id: number) => del<void>(`/admin/visit/dict/${id}`)

export const listVisitRegisters = (params: Record<string, unknown>) =>
  get<PageResult<VisitRegisterRow>>('/admin/visit/register', params)
export const createVisitRegister = (data: Record<string, unknown>) =>
  post<{ id: number }>('/admin/visit/register', data)
export const leaveVisitRegister = (id: number) =>
  post<VisitRegisterRow>(`/admin/visit/register/${id}/leave`)
/** 导出 CSV（带 Authorization，前端用 Blob 下载） */
export const exportVisitRegisters = (params: Record<string, unknown>) =>
  http.get('/admin/visit/register/export', { params, responseType: 'blob' }) as unknown as Promise<Blob>

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

/* ---------------- 车闸相机车牌名单（docs/18，厂商协议待接入） ---------------- */

/** 车牌清单快照：员工白名单 + 当天已授权访客（平台侧"应该下发什么"） */
export const getVehiclePlates = (gateId?: number) =>
  get<VehiclePlateSnapshot>('/admin/vehicle/plate', gateId ? { gateId } : undefined)

/** 下发车牌名单到车闸相机；云车场启用时走 visitor_sync 差分下发 */
export const syncVehiclePlates = (gateId?: number) =>
  post<VehiclePlateSyncResult>('/admin/vehicle/plate/sync', undefined, gateId ? { gateId } : undefined)

/** 车闸车牌下发状态：车场侧已收到的车牌快照（docs/19） */
export const getVehiclePlatePushState = () =>
  get<VehiclePlatePushState>('/admin/vehicle/plate/push-state')

/* ---------------- 通行记录（C-69：行人 + 车辆合并） ---------------- */

export const trafficSummary = (params: Record<string, unknown>) =>
  get<PageResult<TrafficRow>>('/admin/traffic/summary', params)

export const trafficDetail = (personNo: string, date: string) =>
  get<TrafficDetail>(`/admin/traffic/${encodeURIComponent(personNo)}/detail`, { date })