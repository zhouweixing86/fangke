export interface PageResult<T> {
  total: number
  page: number
  size: number
  list: T[]
}

/** 移动端（访客/员工）登录结果 */
export interface AppLoginResult {
  token: string
  role: 'APP_EMPLOYEE' | 'APP_VISITOR'
  subjectId: number
  name: string
  phoneMask: string
  expiresInSeconds: number
}

/** 管理端登录结果 */
export interface AdminLoginResult {
  token: string
  username: string
  displayName: string
  roles: string[]
  expiresInSeconds: number
}

/** 可选门点（访客预约时选从哪个门进） */
export interface GateOption {
  id: number
  gateCode: string
  gateName: string
  gateTypeText: string
}

export interface AppMe {
  role: string
  subjectId: number
  name: string
  phoneMask: string
  personNo?: string
  deptName?: string
  title?: string
  company?: string
  /** 访客：0 未采集人脸 1 已采集 */
  faceStatus?: number
}

export interface AppointmentMate {
  name: string
  idCardMask: string
  main: boolean
}

export interface AppointmentView {
  appointmentNo: string
  status: number
  statusText: string
  source: number
  visitorName: string
  visitorIdCardMask: string
  visitorType: string
  purpose: string
  visitPhoneMask: string
  department: string
  gateId?: number
  gateName?: string
  gateScope?: number
  gateScopeText?: string
  appliedGateNames?: string[]
  approvedGateNames?: string[]
  startTime: string
  endTime: string
  visitDate: string
  mateMode: number
  mateModeText: string
  mates: AppointmentMate[]
  canCancel: boolean
  createdAt: string
}

export interface QrIssueResult {
  code: string
  appointmentNo: string
  expireAt: string
  expireAtEpoch: number
  ttlSeconds: number
  /** PNG 的 Base64（不含 data: 前缀），后端渲染，三端通用 */
  qrImage?: string | null
}

export interface QrVerifyResult {
  allowed: boolean
  reason: string
  appointmentNo: string | null
  visitorName: string | null
  idCardMask: string | null
  visitorType: string | null
  purpose: string | null
  employeeName: string | null
  validFrom: string | null
  validTo: string | null
}

export interface TodoView {
  taskId: number
  level: number
  appointmentNo: string
  visitorName: string
  idCardMask: string
  purpose: string
  startTime: string
  endTime: string
}

export interface ApprovalAction {
  action: number
  actionText: string
  operatorId: string
  comment: string
  at: string
}

export interface ApprovalDetail {
  taskId: number
  level: number
  status: number
  statusText: string
  appointmentNo: string
  appointmentStatus: number
  appointmentStatusText: string
  visitorName: string
  idCardMask: string
  visitorType: string
  purpose: string
  visitPhoneMask: string
  startTime: string
  endTime: string
  gates: ApprovalGateAssignment | null
  canAct: boolean
  actions: ApprovalAction[]
}

/** 审批页门点选择：可选门点 + 访客申请集合 + 当前批准集合 */
export interface ApprovalGateAssignment {
  gateScope: number
  selectable: ApprovalGateOption[]
  appliedGateIds: number[]
  approvedGateIds: number[]
}

export interface ApprovalGateOption {
  id: number
  name: string
}

export interface PassResult {
  appointmentNo: string
  status: number
  statusText: string
  needSecondLevel: boolean
  dispatchStarted: boolean
}

export interface NotificationItem {
  id: number
  templateCode: string
  title: string
  content: string
  bizType: string
  bizNo: string
  read: boolean
  readAt: string
  createdAt: string
}

export interface DeviceView {
  id: number
  deviceSn: string
  mac: string
  deviceName: string
  model: string
  firmware: string
  direction: number
  directionText: string
  gateId: number
  gateName: string
  channel: string
  ip: string
  online: boolean
  lastHeartbeat: string
  personCount: number
  capacity: number
  remark: string
}

export interface SelfCheckItem {
  key: string
  label: string
  status: 'OK' | 'WARN' | 'FAIL'
  detail: string
}

export interface SelfCheckResult {
  deviceSn: string
  model: string
  firmware: string
  channel: string
  checkedAt: string
  status: 'OK' | 'WARN' | 'FAIL'
  items: SelfCheckItem[]
}

export interface EventRow {
  id: number
  eventTime: string
  deviceSn: string
  direction: number
  directionText: string
  identifyType: number
  identifyTypeText: string
  personNo: string
  personName: string
  resultStatus: number
  resultText: string
  source: number
  sourceText: string
  hasMedia: boolean
}

export interface EventMedia {
  id: number
  mediaType: number
  ossKey: string
  expireAt: string
  url: string
}

export interface BlacklistRow {
  id: number
  targetType: number
  targetTypeText: string
  targetMask: string
  reason: string
  validFrom: string
  validTo: string
  status: number
  statusText: string
  createdAt: string
}

export interface ReportOverview {
  from: string
  to: string
  appointments: number
  approved: number
  rejected: number
  pending: number
  withdrawn: number
  checkins: number
  onsiteNow: number
  events: number
  eventPass: number
  eventFail: number
  avgApprovalMinutes: number
  approvalRate: number
  eventPassRate: number
}

export interface ReportDaily {
  date: string
  appointments: number
  checkins: number
  eventPass: number
  eventFail: number
}

export interface GuardDashboard {
  onsite: number
  pendingApproval: number
  devicesOnline: number
  devicesTotal: number
  todayCheckins: number
  deviceStatusText: string
}

export interface OnsiteRow {
  appointmentNo: string
  visitorName: string
  idCardMask: string
  purpose: string
  checkinAt: string
  endTime: string
  overdue: boolean
}

export interface PendingRow {
  appointmentNo: string
  visitorName: string
  idCardMask: string
  purpose: string
  source: number
  sourceText: string
  startTime: string
  endTime: string
  createdAt: string
}

export interface DispatchResult {
  batchId: number
  batchNo: string
  total: number
  success: number
  fail: number
  status: number
  message: string
}
