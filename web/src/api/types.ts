export interface PageResult<T> {
  total: number
  page: number
  size: number
  list: T[]
}

export interface LoginResult {
  token: string
  username: string
  displayName: string
  roles: string[]
  expiresInSeconds: number
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

/** 保安端可选门点（轻量，不含设备与配置） */
export interface GateOption {
  id: number
  gateName: string
  gateCode: string
}

export interface GateRow {
  id: number
  gateCode: string
  gateName: string
  gateType: number
  gateTypeText: string
  status: number
  statusText: string
  sortNo: number
  remark: string
  /** 系统保留的「待分配」门点：不能当授权门点、不能停用 */
  unassigned: boolean
  deviceCount: number
}

export interface DeviceCapabilityRow {
  name: string
  label: string
  supported: boolean
  byAdapter: boolean
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
  capabilities: DeviceCapabilityRow[]
  items: SelfCheckItem[]
}

export interface DeptView {
  id: number
  code: string
  name: string
  parentId: number
  sortNo: number
  /** 该部门下的员工数（有员工时不允许删除） */
  employeeCount: number
}

export interface EmployeeView {
  id: number
  personNo: string
  empNo: string
  name: string
  deptId: number | null
  deptName: string
  title: string
  phoneMask: string
  idCardMask: string
  status: number
  statusText: string
  validFrom: string | null
  validTo: string | null
  faceStatus: number
  faceStatusText: string
  canPassByFace: boolean
  remark: string
}

export interface EmployeeFace {
  id: number
  employeeId: number
  ossKey: string
  ossKeyStd: string
  width: number
  height: number
  sizeBytes: number
  current: boolean
  uploadedBy: number
  createdAt: string
}

export interface ImportRowError {
  row: number
  message: string
}

export interface ImportResult {
  total: number
  success: number
  errors: ImportRowError[]
}

export interface ApprovalConfig {
  id: number | null
  scope: string
  approvalLevel: number
  defaultL1User: number | null
  l2CandidateIds: number[]
  /** 二级复核角色（默认 GUARD=保安），该角色的后台账号都可复核 */
  l2Role: string
  reminderIntervalMin: number
  reminderMaxPerTask: number
}

export interface AppointmentRow {
  id: number
  appointmentNo: string
  visitorName: string
  idCardMask: string
  visitorType: string
  purpose: string
  startTime: string
  endTime: string
  status: number
  statusText: string
  source: number
  sourceText: string
  rejectReason: string
  gateId: number
  gateName: string
  gateScope: number
  gateScopeText: string
  /** 访客申请的门点（'全部门点' 时为空，“/”分隔） */
  appliedGates: string
  /** 审批人最终批准的门点（“/”分隔） */
  approvedGates: string
  grantStatus: number
  canRevoke: boolean
  createdAt: string
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

export interface BlacklistEnforceResult {
  visitorId: number | null
  appointments: number
  cancelled: number
  revoked: number
  failed: number
  message: string
}

export interface StatGroupRow {
  key: string
  total: number
  a: number
  b: number
}

export interface VisitorReport {
  from: string
  to: string
  byDept: StatGroupRow[]
  byType: StatGroupRow[]
}

export interface AccessReport {
  from: string
  to: string
  byDevice: StatGroupRow[]
  byIdentifyType: StatGroupRow[]
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

export interface SyncBatch {
  id: number
  batchNo: string
  triggerType: number
  gateId: number
  totalCount: number
  successCount: number
  failCount: number
  status: number
  startedAt: string | null
  finishedAt: string | null
}

export interface SyncBatchItem {
  id: number
  batchId: number
  deviceId: number
  personNo: string
  action: number
  status: number
  errorCode: string | null
  errorMsg: string | null
  ackedAt: string | null
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

export interface GuardDashboard {
  onsite: number
  pendingApproval: number
  devicesOnline: number
  devicesTotal: number
  todayCheckins: number
  deviceStatusText: string
}

/** 保安复核待办行（C-65：被访人确认后待保安复核） */
export interface ReviewRow {
  taskId: number
  level: number
  appointmentNo: string
  visitorName: string
  idCardMask: string
  purpose: string
  startTime: string
  endTime: string
}

export interface ReviewGateOption {
  id: number
  name: string
}

export interface ReviewAction {
  action: number
  actionText: string
  operatorId: string
  /** 1 员工（App） 2 后台账号（保安/管理员） */
  operatorType: number
  comment: string
  /** 本次审批批准的门点名（留痕对比用） */
  gates: string[]
  at: string
}

export interface GuardReviewDetail {
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
  gates: {
    gateScope: number
    selectable: ReviewGateOption[]
    appliedGateIds: number[]
    approvedGateIds: number[]
  } | null
  canAct: boolean
  actions: ReviewAction[]
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

export interface AdminUserRow {
  id: number
  username: string
  realName: string
  userType: number
  userTypeText: string
  status: number
  statusText: string
  roles: string[]
  lastLoginAt: string
  createdAt: string
}

export interface RoleRow {
  id: number
  roleCode: string
  roleName: string
}

export interface AuditRow {
  id: number
  userId: number
  userName: string
  module: string
  action: string
  target: string
  requestUri: string
  requestIp: string
  detail: string
  result: number
  resultText: string
  createdAt: string
}

export interface AuditOptions {
  modules: string[]
  actions: string[]
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

export interface ReportDailyResult {
  from: string
  to: string
  list: ReportDaily[]
}

export interface JobRunLogRow {
  id: number
  jobCode: string
  startedAt: string
  finishedAt: string
  status: number
  statusText: string
  affected: number
  message: string
}

export interface JobCodeOption {
  code: string
  label: string
}

export interface NotificationRow {
  id: number
  receiverType: number
  receiverTypeText: string
  receiverId: number
  channel: number
  channelText: string
  templateCode: string
  title: string
  content: string
  bizNo: string
  status: number
  statusText: string
  read: boolean
  retryCount: number
  errorMsg: string
  createdAt: string
}

export interface QrIssueResult {
  code: string
  appointmentNo: string
  expireAt: string
  expireAtEpoch: number
  ttlSeconds: number
  channel: string
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
