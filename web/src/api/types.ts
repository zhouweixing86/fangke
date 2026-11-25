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
  /** 角色权限并集（C-73），如 ["appointment:read","guard:*"]；ADMIN 为 ["*"] */
  permissions?: string[]
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
  channelText: string
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
  /** 车牌号（主车，二期车辆识别用） */
  plateNo: string
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
  /** 审核角色（默认 GUARD=保安），该角色的后台账号都可审核 */
  l2Role: string
  reminderIntervalMin: number
  reminderMaxPerTask: number
}

/** 审批节点（管理端配置多级审批） */
export interface ApprovalNodeView {
  id: number
  seq: number
  nodeName: string
  assigneeRole: string
  approveMode: string
  approveModeText: string
  allowEditGates: boolean
  allowQr: boolean
  ccRoles: string[]
}

/** 审批模板：节点串行 + 抄送 */
export interface ApprovalTemplateView {
  id: number
  templateName: string
  scope: string
  enabled: boolean
  remark: string
  nodes: ApprovalNodeView[]
  submitCcRoles: string[]
}

export interface RoleOption {
  roleCode: string
  roleName: string
}

/** 管理端消息中心（审批抄送等） */
export interface MyNotification {
  id: number
  title: string
  content: string
  templateCode: string
  bizNo: string
  read: boolean
  createdAt: string
}

export interface AppointmentRow {
  id: number
  appointmentNo: string
  visitorName: string
  idCardMask: string
  visitorType: string
  purpose: string
  /** 来访区域（visit_dict AREA，C-75）；历史单据为空 */
  areaId: number | null
  areaName: string
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
  /** C-80 审批轨迹（仅详情接口返回） */
  actions?: ApprovalTraceItem[]
}

export interface ApprovalTraceItem {
  action: number
  actionText: string
  operatorName: string
  operatorType: number
  operatorTypeText: string
  comment: string
  gates: string
  at: string
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
  /** 失败记录的原因（如「黑名单拦截」），通过时为空 */
  failReason: string
  idCardMask: string
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
  gateName: string
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
  deviceName: string
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

/** 待审核行（单级：管理端保安/管理员审核） */
export interface ReviewRow {
  taskId: number
  level: number
  /** 节点名称（多级审批，V24） */
  nodeName: string
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
  /** 节点名称（多级审批，V24） */
  nodeName: string
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
  /** 访客是否申请二维码开门 */
  qrRequested?: boolean
  /** 当前是否已同意二维码开门 */
  qrApproved?: boolean
  /** 本节点是否允许调整开放门点 */
  allowEditGates?: boolean
  /** 本节点是否允许审批二维码 */
  allowQr?: boolean
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
  /** C-73：角色描述、权限码、是否内置、状态、在用账号数 */
  description?: string
  perms?: string[]
  builtin?: boolean
  status?: number
  sort?: number
  userCount?: number
}

/** 可分配的页面权限（C-73） */
export interface PermissionItem {
  module: string
  name: string
  group: string
  perm: string
}

/* ---------------- 微信角色二维码（C-76） ---------------- */

export interface WxRoleQr {
  roleId: number
  roleCode: string
  roleName: string
  /** 二维码里的绑定令牌（一角色一个，可重新生成） */
  token: string
  /** 已绑定该角色的微信人数 */
  bindCount: number
}

/** 微信身份（绑定）行（C-77） */
export interface WxBindingRow {
  id: number
  openid: string
  name: string
  phoneMask: string
  employeeId: number | null
  /** 已授予的角色编码，逗号分隔 */
  roleCodes: string | null
  createdAt: string
}

/* ---------------- 审批台账（C-80） ---------------- */

export interface ApprovalRecordRow {
  id: number
  createdAt: string
  appointmentNo: string
  visitorName: string
  nodeName: string
  operatorName: string
  operatorType: number
  operatorTypeText: string
  action: number
  actionText: string
  comment: string
  /** 批准门点，`/` 分隔 */
  gates: string
}

/* ---------------- 来访登记 + 基础字典（V26） ---------------- */

export type VisitDictType = 'CAUSE' | 'AREA' | 'POST'

export interface VisitDict {
  id: number
  dictType: VisitDictType
  name: string
  sort: number
  status: number
  remark: string | null
}

export interface VisitRegisterRow {
  id: number
  areaId: number | null
  areaName: string | null
  causeId: number | null
  causeName: string | null
  visitorName: string
  phoneMask: string | null
  plateNo: string | null
  company: string | null
  personCount: number
  arrivalTime: string
  leaveTime: string | null
  status: number
  remark: string | null
  createdAt: string
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

/** 车辆通行记录（C-68 车牌识别一期） */
export interface VehicleEventRow {
  id: number
  plateNo: string
  gateId: number
  gateName: string
  direction: number
  directionText: string
  eventTime: string
  confidence: number
  matchedType: number | null
  matchedTypeText: string
  matchedName: string
  result: number
  resultText: string
  failReason: string
  photoKey: string
  deviceSn: string
}

/** 车闸车牌名单：平台侧"应该下发什么"（docs/18） */
export interface VehiclePlateRow {
  plateNo: string
  ownerType: string
  ownerTypeText: string
  ownerName: string
  personNo: string
  validFrom: string
  validTo: string
  validToText: string
}

export interface VehiclePlateSnapshot {
  summary: { employee: number; visitor: number; total: number }
  gateId: number
  gateName: string
  plates: VehiclePlateRow[]
}

/** 车牌下发到某台车闸相机的结果；厂商协议未接入时 status=PENDING_VENDOR */
export interface VehiclePlateSyncDevice {
  deviceId: number
  deviceSn: string
  deviceName: string
  gateId: number
  status: string
  message: string
  plateCount: number
}

export interface VehiclePlateSyncResult {
  plateTotal: number
  pendingDevices: number
  devices: VehiclePlateSyncDevice[]
}

/** 通行记录汇总：一人一天一行（C-69） */
export interface TrafficRow {
  personNo: string
  personName: string
  personType: 'EMPLOYEE' | 'VISITOR'
  personTypeText: string
  day: string
  firstIn: string
  lastOut: string
  inCount: number
  outCount: number
  totalCount: number
  channelText: string
  /** 员工所属部门（访客为空） */
  deptName: string
}

/** 某人某天的进出明细 */
export interface TrafficRecord {
  personName: string
  eventTime: string
  channel: 'PEDESTRIAN' | 'VEHICLE'
  channelText: string
  direction: number
  directionText: string
  methodCode: number
  methodText: string
  plateNo: string
  deviceSn: string
  deviceName: string
  gateName: string
  eventId: number | null
}

/** 访客预约单的审批动作 */
export interface TrafficApprovalAction {
  action: number
  actionText: string
  operatorType: number
  operatorRole: string
  operatorName: string
  comment: string
  gates: string[]
  at: string
}

/** 访客当天的申请单 */
export interface TrafficAppointment {
  id: number
  appointmentNo: string
  status: number
  statusText: string
  purpose: string
  visitorType: string
  plateNo: string
  visitEmployeeName: string
  approvedGates: string
  startTime: string
  endTime: string
  createdAt: string
  actions: TrafficApprovalAction[]
}

export interface TrafficDetail {
  personNo: string
  date: string
  personName: string
  personType: 'EMPLOYEE' | 'VISITOR'
  personTypeText: string
  /** 员工字段 */
  deptName?: string
  empNo?: string
  title?: string
  validFrom?: string
  validTo?: string
  statusText?: string
  /** 访客字段 */
  company?: string
  faceStatus?: number
  appointments?: TrafficAppointment[]
  /** 公共字段 */
  phoneMask?: string
  plateNo?: string
  records: TrafficRecord[]
}
