# visitor-server

访客管理系统服务端。当前已实现：**M1 基础框架 + M2 员工管理 + M3 设备中心（局域网通道）+ M4 预约 + M5 审批流 + M6 授权下发 + M11 保安工作台 + 管理端登录**。

- 接口契约：[../docs/11-接口详细契约.md](../docs/11-接口详细契约.md)
- 数据模型：[../docs/10-数据库设计.md](../docs/10-数据库设计.md)
- 架构与计划：[../docs/09-开发计划与技术架构.md](../docs/09-开发计划与技术架构.md)
- 设备协议：[../docs/06-设备对接协议-局域网HTTP.md](../docs/06-设备对接协议-局域网HTTP.md)

## 1. 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17+ |
| Maven | 3.9+ |
| MySQL | 8.0+（P1 需要，Flyway 自动建表） |

> 首次运行会自动执行 `src/main/resources/db/migration/V1__init.sql` 与 `V2__seed.sql`。

## 2. 本地启动

```bash
# 建库（只需一次）
mysql -uroot -p -e "CREATE DATABASE fangke DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 启动
set DB_USER=root
set DB_PASSWORD=你的密码
mvn spring-boot:run
```

启动后默认监听 `http://localhost:8080`。

## 3. 关键环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | 127.0.0.1 / 3306 / fangke | 数据库 |
| `DB_USER` / `DB_PASSWORD` | root / root | 数据库账号 |
| `JWT_SECRET` | dev-only-change-me | **生产必须替换** |
| `QR_SECRET` | dev-only-qr-secret | 二维码签名密钥，按环境隔离 |
| `DEVICE_DEFAULT_PASSWORD` | 123456 | 设备出厂口令 |
| `S3_ENDPOINT` | http://127.0.0.1:9000 | S3 兼容端点（默认本机 MinIO） |
| `S3_BUCKET` | fangke | 桶名，启动时不存在会自动创建 |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | 空 | MinIO/云厂商的 AK/SK；留空时照片功能降级并告警 |
| `S3_REGION` | us-east-1 | MinIO 固定 us-east-1 |
| `CRYPTO_AES_KEY` / `CRYPTO_HMAC_KEY` | dev 占位 | 敏感字段加密与摘要密钥，**生产必须替换且两把不同** |

设备回调来源白名单在 `application.yml` 的 `fangke.device.callback-allowed-cidrs` 配置，例如 `192.168.10.0/24`。
**生产要求**：`/api/device/callback/**` 只允许设备网段访问，不对公网暴露（设备不发签名头，见 docs/11 §0.4）。

## 4. 已实现（P1）

### M1 基础框架

| 能力 | 位置 |
|---|---|
| 统一响应 / 分页 | `common/ApiResponse`、`common/PageResult` |
| 错误码全表（40 条） | `common/ErrorCode` |
| 全局异常 → 统一响应 | `common/GlobalExceptionHandler` |
| JWT 签发与校验（HS256，无第三方依赖） | `security/JwtService` |
| 后台/保安端鉴权拦截（含**集中式 ADMIN 校验**） | `security/AuthInterceptor` + `config/WebConfig` |
| 审计切面（写 `audit_log`，失败不影响主流程） | `audit/AuditLog`、`audit/AuditAspect` |
| 配置外置 | `config/AppProperties` |
| 对象存储（MinIO/S3，path-style、预签名、启动自动建桶） | `storage/StorageService`、`S3StorageService` |
| 抓拍照片异步上传 + `access_event_media` 落库 | `device/service/CaptureUploader`、`S3CaptureUploader` |
| 签到 / 签离（按设备方向判定，回写 visit_record 与预约单状态） | `device/service/VisitRecordService` |
| 名单对账（三向比对 + 多余人员直接删除） | `device/service/ReconcileService` |
| 每日对账定时任务（02:00） | `device/service/ReconcileJob` |
| 通行记录照片查看（预签名 URL） | `device/callback/AccessMediaController` |

### M3 设备中心

| 能力 | 位置 |
|---|---|
| 双通道适配接口（12 个语义方法） | `device/adapter/DeviceAdapter` |
| 云通道（P5 实现，显式抛错） | `device/adapter/CloudMqttAdapter` |
| 按 `channel` 选适配器 | `device/adapter/DeviceAdapterFactory` |
| 同设备指令**串行** + 指数退避重试 | `device/gateway/DeviceGateway` |
| 设备日期格式（`yyyy-MM-dd` + 尾随空格） | `device/adapter/DeviceDateFormats` |
| 心跳接收 | `device/callback/DeviceCallbackController#heartbeat` |
| 通行记录接收（先落库再 ack、`event_uuid` 幂等） | `#record` + `service/AccessRecordService` |
| 在线比对（二维码 7 步判定） | `#compare` + `service/OnlineCompareService` |
| 二维码验签（HMAC8） | `service/QrCodeVerifier` |
| 授权状态判定 | `service/GrantChecker` + `JdbcGrantChecker` |
| 回调来源 IP 白名单 | `device/callback/DeviceCallbackGuard` |
| 管理端：设备列表 / 实时读参数 / 参数合并下发 / 远程开门 / 屏幕提示 | `device/callback/DeviceAdminController` |
| **设备能力声明与按台覆盖**（换机预埋，见 docs/12） | `device/model/DeviceCapability`、`device/service/DeviceCapabilityService` |
| **接入自检**（通道/能力/版本/参数/名单逐项 OK·WARN·FAIL） | `device/service/DeviceSelfCheckService`、`POST /api/admin/device/{id}/selfcheck` |
| **零设备联调通道**（`channel=MOCK`，内存名单） | `device/adapter/MockDeviceAdapter` |
| **轮询式终端适配器**（`channel=PULL`，新采购设备） | `device/adapter/OpenApiPullAdapter` |
| **轮询式终端接口**（13 个 `/openapi/v1/**`，设备主动调用） | `device/pull/DeviceOpenApiController` |
| 轮询式终端业务（心跳派发 action、名单版本、记录、二维码） | `device/pull/OpenApiDeviceService` |
| 设备指令队列（复用 `device_cmd`，心跳交付 + 超时回收） | `device/pull/JdbcPullCommandRepository` |
| 设备请求签名（MD5 大写，data 不参与） | `device/pull/OpenApiSign` |
| 新终端厂商字段映射（openType→identify_type 等） | `device/pull/OpenApiRecordMapper` |

> 换设备只需新增一个 `DeviceAdapter` 实现并如实声明 `capabilities()`：能力不足的指令统一返回 **4006**（带型号与能力中文名），不会抛 500，也不需要改业务代码。完整流程见 [../docs/12-新设备接入指南.md](../docs/12-新设备接入指南.md)。

#### 通道（只剩两种）

| | `PULL`（轮询式终端，唯一在用） | `MOCK`（自测） |
|---|---|---|
| 谁主动 | **设备连平台** `/openapi/v1/**` | 无 |
| 下发名单 | 入 `device_cmd` 队列 → 心跳取 `action` → 设备回来拉详情 | 内存 |
| 远程开门 | 入队，延迟 = 心跳间隔（默认 30s） | 立即返回 |
| 读设备参数/名单/版本 | ❌ 协议不支持 | ✅ |
| 二维码核验 | 设备推 `qrCodePush`，平台回 `openGate` | ✅ |
| 签名 | 顶层字段 MD5（`fangke.device.pull-sign-key`） | 无 |

> **九鼎 YC-P208（局域网推送、8091 回调）已整体下线**：`LanHttpAdapter`、`DeviceCallbackController`、
> 心跳/记录/在线比对服务、对账（`/resync`）、自检（`/selfcheck`）、参数读写、屏幕提示，
> 以及 06/07/08 三份厂商文档全部移除。管理端设备页只保留「档案」和「开门」。
>
> PULL 通道的设备**首次心跳会自动登记**（mac 先占位 `PULL-<SN>`），落在「待分配」门点，
> 必须用 `PUT /api/admin/device/{id}` 分配到真实门点，否则该门点的名单不会下发到它。
> 协议细节见 [../docs/14](../docs/14-设备协议-新终端HTTP轮询版.md)，接入步骤见
> [../docs/12](../docs/12-新设备接入指南.md)。
#### 多门点（厂区 4 个门）

| 行为 | 实现 |
|---|---|
| 门点维护 | `/api/admin/gate`（GET/POST/PUT/PUT status）+ 管理端「门点管理」页 |
| 设备归属 | `device.gate_id`；`GET /api/admin/device?gateId=` 过滤，不传返回全部 |
| 申请范围 | `POST /api/app/appointment` 带 `gateScope`（1 指定 / 2 全部）+ `gateIds`（多选） |
| 审批调整 | `POST /api/app/approval/{taskId}/pass` 带 `approvedGateIds`（不传＝继承申请，可增可减，两个集合都落库留痕） |
| 授权签发 | `access_grant` **每个批准门点一行**；撤销/过期按单全量处理 |
| 核验 | `JdbcGrantChecker` 判定「该门点是否在有效授权集合内」，跨门仍返回 `GATE_MISMATCH` |
| 无设备门点 | 降级跳过不阻塞；授权仍为 ACTIVE，设备装机后由对账 `expectedOn(gate)` 补齐 |
| 访客选门 | `GET /api/app/gate`（公开） |
| 保安开门 | `GET /api/guard/gate` 选门 → `POST /api/guard/door/open` 带 `gateId` |
| 保留门点 | `UNASSIGNED / 待分配`：不能授权、不能停用、不能改类型 |

### M2 员工管理

| 能力 | 位置 |
|---|---|
| 员工增删改查（工号唯一、personNo=E+6位工号） | `employee/service/EmployeeService` |
| 身份证/手机号 **AES-GCM 加密 + HMAC 唯一索引 + 脱敏展示** | `security/CryptoService` |
| 证件号校验位、手机号格式校验 | `employee/service/EmployeeValidator` |
| Excel 批量导入（逐行错误 + 行号） | `EmployeeService#importExcel` |
| 照片校验（≤500KB、400×400~1080×1920）+ 压缩到 ≤200KB | `employee/service/PhotoValidator` |
| 照片绑定：直传预签名 / 表单上传两条路径 | `employee/web/EmployeeController`、`UploadController` |
| 换照片保留历史（旧记录 is_current=0） | `employee_face.is_current` |
| 停用即取消通行资格 | `EmployeeService#stop` |
| 部门管理（增删改，逻辑删除，有员工/子部门时拒删） | `employee/service/DeptService`、`employee/web/DeptController` |
| **员工名单下发**（入职即建全门点长期授权 + 推送含照片，离职撤销） | `employee/service/EmployeeDispatchService` |
| 手动补偿：单人 / 一键全量同步 | `POST /api/admin/employee/{id}/sync`、`/sync-all` |

> 员工授权是 `access_grant(subject_type=1)`，每人每个启用门点一行，`appointment_id` 为空；
> 有效期取员工档案的入职/离职日期（没填则 9999-12-31）。
> 换照片、Excel 导入、停用都会自动触发同步；新增门点后点一次「同步名单」补历史员工。

### M4 访客与预约

| 能力 | 位置 |
|---|---|
| 访客档案按**身份证号 HMAC 复用**（同一人只有一条） | `appointment/service/VisitorService` |
| 隐私同意单独落库（visitor_consent，不可删） | `VisitorService#agree` |
| 预约提交（访客自约 / 现场自助登记 / 员工代邀） | `appointment/service/AppointmentService` |
| 时段校验：不早于今天、最多提前 30 天、代邀 ≤7 天、结束晚于开始 | `AppointmentService#validate` |
| 访客类型只允许 商务/施工/送货；事由 ≤100 字；车牌 ≤16 字符 | 同上 |
| 同行人两种模式（随行 / 各自刷证，后必须填身份证） | 同上 |
| 黑名单拦截（证件号 + 手机号，按 HMAC 等值查询） | `BlacklistRepository` |
| 重复进行中预约拦截（3002） | `findInProgress` |
| 撤回（仅待审批可撤回） | `AppointmentService#cancel` |
| 审批启动钩子（M5 实现，当前空实现） | `ApprovalStarter` / `ApprovalStarterConfig` |

### M5 审批流

| 能力 | 位置 |
|---|---|
| 一级审批人 = 被访人；未匹配用配置的默认审批人（BR-A-002） | `approval/service/ApprovalService#start` |
| **双审（C-65）**：一级被访人确认 → 生成 **GUARD 角色任务**（保安复核），此时**不下发**授权 | `ApprovalService#pass` |
| 保安复核：`/api/guard/approval/**`，通过才下发、驳回整单驳回 | `ApprovalService#guardPass` / `#guardReject` |
| 二级是角色任务（`assignee_type=2`、`assignee_role=GUARD`、`assignee_id=0`），轮班谁值班谁批 | `approval_task` |
| 两个环节都可增减门点，每次落 `approval_action.gate_snapshot` 留痕 | `ApprovalService#gateSnapshot` |
| 驳回必须填意见，驳回后其余待办失效 | `ApprovalService#reject` |
| 转办 / 加签（同层并行，全部通过才终审） | `ApprovalService#transfer` / `#countersign` |
| 催办限流（间隔 30 分钟、上限 2 次，BR-N-007） | `ApprovalService#remind` |
| 待办角标与列表（唯一可靠通知通道，BR-N-002） | `todoCount` / `todoList` |
| 终审通过 → 触发授权下发（M6 钩子） | `DispatchTrigger` |
| 审批配置（级数/默认审批人/复核角色/催办参数） | `ApprovalAdminController` |

### M6 授权下发

| 能力 | 位置 |
|---|---|
| 终审通过 → 生成授权（**按自然日整段 00:00–23:59**，C-60） | `dispatch/service/DispatchService` |
| 事务提交后再异步下发（避免读到旧状态） | `DispatchService#trigger` |
| 逐设备下发 + 批次与明细留痕 | `sync_batch` / `sync_batch_item` |
| 全部成功才授权生效、预约单置「可通行」 | `DispatchService#dispatch` |
| 撤销授权：设备删除 + 授权撤销 + 预约单撤销 | `DispatchService#revoke` |
| 过期 7 天名单自动清理（JOB-05） | `DispatchCleanupJob` |
| 批次查询 / 手动重下 / 撤销 | `dispatch/web/DispatchAdminController` |
| 访客 usertype 可配置（实测必须用 `white`） | `fangke.device.visitor-user-type` |

### M11 保安工作台

| 能力 | 位置 |
|---|---|
| 首屏看板（在访、待审批、设备在线、今日到访） | `guard/service/GuardService#dashboard` |
| 在访列表（含入场时间、超时标记） | `GuardService#onsite` |
| 自助登记待审批列表（**只读**，保安无审批权） | `GuardService#pending` |
| 催办（复用审批模块限流：30 分钟最多 2 次） | `GuardService#remind` |
| 人工开门（访客须已授权；员工须填核验说明） | `GuardService#openDoor` |
| 手工签离 | `GuardService#manualCheckout` |
| 远程开门设备调用 | `guard/service/DeviceDoorOpener` |
| 角色校验（需 GUARD 或 ADMIN） | `guard/web/GuardController` |

### 认证与登录（管理端）

| 能力 | 位置 |
|---|---|
| 登录签发 JWT（BCrypt 校验） | `auth/service/AuthService`、`auth/web/AuthController` |
| 登录失败锁定（同账号 10 分钟内连续 5 次失败锁定 10 分钟，内存计数） | `AuthService` |
| 首次启动自动建管理员（账号表为空时） | `auth/service/BootstrapAdminInitializer` |
| 跨域（管理端独立部署时用） | `config/WebConfig` + `fangke.cors.allowed-origins` |

> 初始管理员：账号 `admin`，口令取 `BOOTSTRAP_ADMIN_PASSWORD`（默认 `Admin@123456`），**首次登录后必须修改**。

### M9 黑名单管理

| 能力 | 位置 |
|---|---|
| 列表（脱敏值/原因检索、状态筛选、分页） | `admin/repository/JdbcAdminBlacklistRepository`、`admin/web/AdminBlacklistController` |
| 新增（证件号校验位、手机号格式、原因必填 ≤255、有效期区间校验、重复拉黑拦截） | `admin/service/BlacklistAdminService#create` |
| 失效 / 恢复（不物理删除，保留拉痕） | `#updateStatus` |
| **立即生效（BR-B-003）**：撤回在途预约、撤销授权并删设备名单、作废通行码 | `#enforce`、`POST /api/admin/blacklist/{id}/enforce` |
| 三件套落库（AES-GCM 密文 + HMAC 唯一索引 + 脱敏） | `security/CryptoService` |
| 命中判定（预约提交时只查 HMAC） | `appointment/repository/JdbcBlacklistRepository` |

### M9 账号、角色与审计

| 能力 | 位置 |
|---|---|
| 账号列表/新建/改资料与角色/启用停用/重置密码 | `admin/service/UserAdminService`、`admin/web/AdminUserController` |
| 口令规则（8~64 位含字母数字、BCrypt）与账号名规则 | `UserAdminService#validatePassword` |
| 最后一个管理员保护（不能停用/降权到没人能进后台） | `#isLastActiveAdmin` |
| 本人改密码（验旧口令、新旧不能相同） | `#changeOwnPassword`，入口在后台右上角菜单 |
| 角色校验（账号/审计/报表仅 ADMIN） | `security/RoleGuard` |
| 审计日志查询（模块/动作/账号/时间/IP/关键词） | `admin/repository/JdbcAdminAuditRepository`、`admin/web/AdminAuditController` |
| 审计写入带操作人（`sub` → `user_name`） | `audit/AuditAspect` + `AuthInterceptor.ATTR_USER_NAME` |

### M9 报表统计

| 能力 | 位置 |
|---|---|
| 概览（预约/通过/驳回/待审/撤销、到访人次、当前在访、通行成功率、通过率、平均审批时长） | `admin/web/AdminReportController#overview` |
| 逐日明细（区间内每天一行，无数据补 0） | `#daily`、`JdbcAdminReportRepository` |
| 访客维度（按部门 / 按访客类型：预约、通过、到访） | `GET /api/admin/report/visitor` |
| 通行维度（按设备 / 按识别方式：事件、通过、失败） | `GET /api/admin/report/access` |
| 区间校验（默认近 30 天，最长 366 天） | `admin/service/ReportService` |

### M7 动态二维码（平台侧）

| 能力 | 位置 |
|---|---|
| 签发（60 秒、绑定生效授权）+ 撤销 | `qr/service/QrTicketService`、`qr/web/QrAdminController` |
| 保安端现场签发 + 扫码/手输核验 | `qr/web/QrGuardController` |
| 码格式与验签（HMAC8，复用设备端同一套） | `device/service/QrCodeVerifier` |
| 票据（只存 SHA256 摘要 + 使用次数） | `qr/model/QrTicket`、`qr/repository/JdbcQrTicketRepository` |
| 二维码 PNG（Base64，供三端 `<image>` 直接显示） | `qr/service/QrImageService`（zxing），接口返回 `qrImage` |
| 核验留痕（放行/拒绝+原因） | 写 `access_event`（`source=4` 保安核验、`identify_type=5` 二维码） |
| 核验通过 → 签到 | `VisitRecorder`（门点取授权上的 `gate_id`） |
| 每日 03:20 置过期 + 删 7 天前老票 | `QrTicketService#housekeeping` |
| 撤销授权时联动作废未用码 | `dispatch/web/DispatchAdminController#revoke` |
| 批次重试（按 personNo 反查预约后重建报文重下） | `DispatchService#retryBatch`、`POST /api/admin/sync/batch/{id}/retry` |
| 授权查询（谁能进门/何时过期） | `GET /api/admin/grant` |

> 口径按需求文档：**60 秒有效（BR-V-019）、有效期内可重复使用（BR-V-020）、失败必须留痕并区分原因（BR-V-024）**。

### M10 访客端 / 员工端（H5）

| 能力 | 位置 |
|---|---|
| 手机号 + 验证码登录（开发模式回显、生产缺网关直接失败） | `app/auth/service/AppAuthService`、`app/auth/web/AppAuthController` |
| App token 鉴权（`APP_EMPLOYEE` / `APP_VISITOR`） | `security/AppAuthInterceptor` + `config/WebConfig` |
| 我的信息 | `app/web/AppMeController` |
| 我的预约 / 详情 / 撤回（归属校验） | `appointment/web/AppVisitorController` |
| 访客取通行码（复用 M7，`channel=APP`） | `qr/web/QrAppController` |
| 员工待办 / 通过 / 驳回（token 身份，`X-Employee-Id` 仅兜底） | `approval/web/ApprovalController` |

> 公开接口只有 登录、隐私同意、现场登记、首次提交预约；其余都要登录。
> 移动端代码在 [../uniapp/](../uniapp/)（uni-app，H5/小程序/APP 一套代码，H5 端口 5175）。

### 通知中心（M9 补全）

| 能力 | 位置 |
|---|---|
| 站内信 + 短信（先落库再发送，失败可重试） | `notify/service/NotifyService` |
| 去重（`dedup_key` 唯一索引，同事件只发一次） | `notify/repository/JdbcNotificationRepository` |
| 模板文案（审批待办/结果/催办/过期/到场） | `notify/model/NotifyTemplates` |
| 审批事件 → 通知（替代原 noop 实现） | `notify/service/DbNotifyPort` |
| 短信通道抽象（当前日志通道，可换阿里云） | `notify/sms/SmsSender`、`LoggingSmsSender` |
| 移动端消息中心（列表/未读数/已读） | `notify/web/AppNotifyController` |
| 管理端通知记录（站内+短信全量，排查用） | `notify/web/AdminNotifyController` |
| 系统配置（白名单业务参数，敏感 key 拒绝写库） | `admin/web/AdminConfigController`、`admin/service/ConfigAdminService` |
| 通行记录 CSV 导出（UTF-8 BOM，上限 1 万条） | `GET /api/admin/access/event/export` |

### 定时任务（M12）

| 任务 | 编码 | 默认节奏 | 作用 |
|---|---|---|---|
| 审批超时催办 | `APPROVAL_REMIND` | 每 10 分钟 | 超过 120 分钟未处理的待办自动催办（受催办上限/间隔约束） |
| 预约过期 | `APPOINTMENT_EXPIRE` | 每小时 :05 | 过了结束时间仍未到访的预约置「已过期」并站内通知访客 |
| 在访超时签离 | `VISIT_AUTO_CHECKOUT` | 每小时 :15 | 在访但已过结束时间的记录异常离场（`checkoutType=3`） |
| 短信重试 | `NOTIFICATION_RETRY` | 每分钟 | 失败短信重发，最多 3 次 |
| 保留期清理 | `DATA_RETENTION` | 每天 04:40 | 心跳 7 天 / 已读通知 30 天 / 通知 180 天 / 任务日志 30 天 |

| 能力 | 位置 |
|---|---|
| 统一执行包装（计时、记 `job_run_log`、吞异常） | `job/service/JobRunner` |
| 调度与手动执行（`runOnce`） | `job/service/JobScheduler` |
| 维护逻辑（过期/签离/清理） | `job/service/MaintenanceService` |
| 自动催办 | `job/service/ApprovalReminderService` |
| 运维接口（日志/字典/手动执行） | `job/web/AdminJobController` |

> 所有 cron 与阈值都可用环境变量覆盖（`fangke.job.*`），例如 `APPROVAL_REMIND_CRON`。
> 已有任务（对账、授权过期清理、二维码票据清理）仍在各自模块，同样可在「运维中心」看到执行结果。

### 自动化巡检（tools/autopilot）

| 能力 | 位置 |
|---|---|
| 14 个按模块拆分的独立任务（测试/扫描/文档一致性） | `tools/autopilot/tasks.json` |
| 9 个确定性扫描器（密钥、迁移、端点、鉴权、二维码、依赖、索引、TODO、文档进度） | `tools/autopilot/scanners.ps1` |
| 执行器（跑检查 → 出报告 → 按策略唤醒代理） | `tools/autopilot/runner.ps1` |
| Windows 计划任务注册（默认不唤醒，避免消耗额度） | `tools/autopilot/register-schedule.ps1` |
| 误报豁免（必须写理由） | `tools/autopilot/allowlist.json` |

```powershell
cd tools\autopilot
.\runner.ps1 -Mode plan                 # 看哪些任务到期
.\runner.ps1 -Mode run -Task T04        # 只跑安全检查
.\runner.ps1 -Mode run -Task T04 -Wake  # 跑完唤醒代理修复
.\register-schedule.ps1                 # 每天 09:10 自动跑一个到期任务
```

> 报告与状态落在 `.autopilot/`（已 gitignore）。完整说明见
> [tools/autopilot/README.md](../tools/autopilot/README.md)。

### 尚未实现（按里程碑）

| 阶段 | 内容 |
|---|---|
| P3 | 动态二维码签发（M7）、访客端/员工端接口、小程序登录 |
| P5 | 云通道 `CloudMqttAdapter` |
| 待补 | 对账缺失自动补推（等 M7/M10 的证件号通道）、访客端鉴权 |

## 5. 设备回调（联调用）

平台需要能被设备访问到，且端口放行。在设备上配置：

| 项 | 值 |
|---|---|
| 心跳地址 | `http://<平台IP>:8080/api/device/callback/heartbeat` |
| 记录回调 | `http://<平台IP>:8080/api/device/callback/record` |
| 在线比对地址 | `http://<平台IP>:8080/api/device/callback/compare/online` |

> 设备侧 `heartBeatEnable=0` 为开、`platformEnable=0` 为开（两处语义相反，见 docs/06 §0.3）。

用仓库里的联调脚本可直接下发配置（`-PathPrefix` 指向本服务的回调前缀）：

```powershell
..\tools\device-probe.ps1 -Device 192.168.10.135 -Action heartbeat -PlatformIp 192.168.10.50 -PlatformPort 8080 -PathPrefix /api/device/callback
..\tools\device-probe.ps1 -Device 192.168.10.135 -Action callback  -PlatformIp 192.168.10.50 -PlatformPort 8080 -PathPrefix /api/device/callback
```

下发后设备实际会请求：

- `POST http://192.168.10.50:8080/api/device/callback/heartBeat`
- `POST http://192.168.10.50:8080/api/device/callback/getrecord/`

> 路径大小写已同时兼容：设备按我们给的地址原样 POST，控制器同时映射了
> `/heartbeat` 与 `/heartBeat`、`/record` 与 `/getrecord/`，所以脚本默认拼出的地址可直接用。

## 6. 测试

```bash
mvn test
```

当前 **219 个用例**，覆盖：

| 用例 | 锁住的行为 |
|---|---|
| `DeviceDateFormatsTest` | 下发日期必须带尾随空格 |
| `LanHttpAdapterTest` | 访客/员工下发报文、参数合并下发、设备错误码映射、名单 JSON 解包 |
| `DeviceCallbackContractTest` | 回调返回体**不得**套统一响应；记录失败回 `result=1`；比对异常不开门；**大写 `SN`/`employee_number` 等原始字段必须能绑定** |
| `HeartbeatServiceTest` | 在线状态、容量告警、时钟偏差、口令错误不影响应答 |
| `AccessRecordServiceTest` | 幂等、证件号脱敏、缺 id 兜底、**base64 剥离 + 转交对象存储**、非法 raw 置空 |
| `VisitRecordServiceTest` | 进场签到、重复进场忽略、离场签离、未签到离场、失败事件、无预约人员、异常离场 |
| `ReconcileServiceTest` | 三向比对、缺失只记录、多余直接删除、状态落库、设备不存在抛错、上线异步触发 |
| `CryptoServiceTest` | 加解密可逆、随机 IV、HMAC 稳定、脱敏格式、空值 |
| `PhotoValidatorTest` | 分辨率上下限、500KB 上限、非图片、压缩到 200KB 内仍可解码 |
| `EmployeeServiceTest` | 工号/手机号/证件号唯一与格式、personNo 规则、加密脱敏、换照片历史、停用、预签名、列表脱敏、Excel 导入 |
| `AppointmentServiceTest` | 自约/代邀/现场登记、日期与时段校验、访客类型、同行人两种模式、黑名单、重复预约、撤回、档案复用 |
| `ApprovalServiceTest` | 启动审批、单级/两级流转、自动选中、同级自动跳过、驳回必填意见、越权与重复处理、转办、加签并行、催办限流、待办角标、配置校验；**双审（C-65）：一级确认后生成 GUARD 角色任务且不下发、保安复核通过才下发、员工端不能处理保安任务（3006）、无角色不能复核、保安驳回整单驳回** |
| `DispatchServiceTest` | 下发成功/部分失败/无设备、有效期整段、证件号解密、重复下发幂等、撤销、过期清理、**事务提交后才下发（防竞态）**、**多门点按批准集合逐门建授权且无设备门点降级跳过** |
| `GuardServiceTest` | 看板文案、超时标记、来源文案、催办转交、未授权拒开、授权过期拒开、员工代开门需填原因、设备失败上抛、手工签离与重复签离 |
| `QrCodeVerifierTest` | 验签、过期、篡改、格式、长度约束 |
| `OnlineCompareServiceTest` | status 2/3/4/5/6 与放行 |
| `DeviceCallbackGuardTest` | CIDR 匹配与非法输入拒绝 |
| `DeviceCapabilityServiceTest` | 适配器声明 ∩ 设备覆盖（disable/only/裸数组）、非法 JSON 容错、4006 提示含型号与能力名、名单关键能力缺失检测 |
| `DeviceSelfCheckServiceTest` | 自检全绿、参数缺字段 WARN、能力缺失 FAIL、设备不通逐项 FAIL、无适配器通道提示 |
| `GlobalExceptionHandlerTest` | 设备异常映射 503/501/502，业务异常映射不变（此前设备异常会落成 5001） |
| `BlacklistAdminServiceTest` | 三件套落库（可解密还原）、证件号校验位/手机号、重复拉黑、原因必填与超长、有效期区间、失效/恢复；**立即生效**：无档案提示、待审撤回、已通过撤销+作废码、无授权直接置撤销、已失效拒绝 |
| `UserAdminServiceTest` | 账号名/口令强度/角色字典校验、最后一个管理员保护、不能停用自己、重置与自助改密码 |
| `ReportServiceTest` | 默认近 30 天、只传结束日回溯、区间反向与超 366 天拒绝、透传仓储 |
| `QrTicketServiceTest` | 签发前提（授权/时段）、摘要落库、**60 秒内可重复使用**、撤销/过期/篡改/非本平台码拒绝、门点缺失只留事件、清理任务 |
| `AppAuthServiceTest` | 验证码 60 秒限流、生产缺网关报 5004、手机号格式、验证码错误/一次性、员工/访客身份判定、停用员工拒绝、未登记提示 |
| `NotifyServiceTest` | 站内+短信双写、同事件去重、短信失败不拖累站内、失败重试置已发、未读/已读、分页 |
| `MaintenanceServiceTest` | 预约过期置状态并通知、在访超时异常签离、空数据不动库、JobRunner 成功/失败日志包装 |
| `ApprovalReminderServiceTest` | 超时待办逐个催办、达到上限跳过、无待办不调用 |
| `AuthInterceptorTest` | `/api/admin/**` 集中式 ADMIN 校验（GUARD/VISITOR_ADMIN 403）、`/api/admin/user/me/**` 例外、缺 token/过期 401 |
| `OpenApiSignTest` | 新终端签名：排除 data/sign、ASCII 升序、末尾 &key=、MD5 大写；篡改顶层字段必须失败 |
| `OpenApiRecordMapperTest` | openType→identify_type（含未知值兜底）、时间解析容错、证件号脱敏、幂等键优先级 |
| `OpenApiDeviceServiceTest` | 心跳 action 优先级、开门下发后立即出队（不重放）、自动登记与 mac 占位（**落「待分配」门点**）、名单索引/详情、回执出队 |
| `EmployeeDispatchServiceTest` | 在岗员工按启用门点各建一条长期授权（排除待分配）、推送到有设备的门点、停用员工不推送只置撤销、停用清理从设备删除、全量同步覆盖在岗+停用 |
| `DeptServiceTest` | 部门编码统一转大写且唯一、编码格式/名称必填、上级必须存在且不能是自己、有员工/有子部门的部门不能删、空部门可删 |
| `GateServiceTest` | 「待分配」不能授权/不能停用/不能改类型、默认门点配置失效回落第一个可选门、停用门点不可选与不可挂设备、编码唯一与保留码、编码统一大写 |
| `AppointmentGateServiceTest` | 申请「全部门点」展开成当时启用门点、多选校验去重、不许申请「待分配」、不传门点回落默认、审批可增可减且申请集合不动、不传则继承、清空被拒、历史单据兜底 |

## 7. 与文档的差异（需注意）

| 项 | 文档 | 当前实现 | 原因 |
|---|---|---|---|
| DAO | MyBatis-Plus | `JdbcTemplate` | P1 表少、SQL 直观；仓储已抽成接口，后续可平滑替换 |
| 对象存储 | 先 MinIO 后可切云 OSS | 已接入 AWS S3 SDK（MinIO path-style），云端只需改配置 | 两个都支持 |
| 云通道 | MQTT | 显式抛错 | P5 实现，防误配后静默失败 |
| 访客 usertype | 协议文档写 `visitor` | 默认下发 `white` | 实测固件对 `visitor` 不落库（06 §0.3 第 6 条） |
| 访客端鉴权 | 微信登录态 | **暂未鉴权**（`/api/app/**` 开放），审批接口用 `X-Employee-Id` 头模拟登录员工 | 小程序登录在 P3 接入，上线前必须补 |

## 8. 端到端验证记录（2026-11-16，真实 MySQL 8.0.30）

环境：`fangke` 库（Flyway 自动建 15 表 + 种子数据）、服务监听 8080、设备档案取自真机（SN `T77R781116ZS`）。

| # | 场景 | 请求 | 实际结果 |
|---|---|---|---|
| 1 | Flyway 迁移 | 启动应用 | `V1 init`、`V2 seed` 均 success；15 张表；设备/门点/角色种子就绪 |
| 2 | 心跳 | `POST /api/device/callback/heartbeat`（真实报文，含大写 `SN`） | `{"message":"ok","result":0}`；`device.online_state=1`、`person_count=3`、`device_heartbeat` 落 1 行 |
| 3 | 记录幂等 | 同一 `id=evt-e2e-001` 连发 2 次 | 两次都回 ok；`access_event` **仅 1 行**（`uk_event_uuid` 生效） |
| 4 | 证件号脱敏 | 同上 | 落库 `110101********1234`；中文姓名 `张三` 正确存储 |
| 5 | 比对-无效码 | `type=qr` + 任意数字串 | `{"status":2,"msg":"二维码无效"}` |
| 6 | 比对-过期码 | 已过期签名码 | `{"status":3,"msg":"二维码已过期，请刷新"}` |
| 7 | 比对-无授权 | 有效签名，但库中无对应预约 | `{"status":4,"msg":"无通行授权"}` |
| 8 | **比对-放行** | 插入 `appointment(status=35)` + `access_grant(status=1, gate=1, 当天 00:00–23:59)`，再发有效码 | `{"status":0,"msg":"识别成功"}` ✅ 全链路（验签→授权→门点→时段）打通 |
| 9 | 鉴权 | `GET /api/admin/device` 不带 token | `HTTP 401` + `{"code":2001,"message":"未登录或 token 失效","traceId":"..."}` |
| 10 | UTF-8 | 用 `curl --data-binary @file` 抓原始字节 | 响应为 UTF-8 字节（`E7 B3 BB...`），设备端可正常显示中文 |

### 运行应用才暴露的两个 bug（单测未覆盖，已修）

| 问题 | 现象 | 修复 |
|---|---|---|
| `LanHttpAdapter` 有两个构造器且都没标注，Spring 找不到默认构造器 | 启动直接失败 `No default constructor found` | 在 `(ObjectMapper, AppProperties)` 构造器上加 `@Autowired` |
| 心跳 DTO 未映射大写 `SN` | 启动后能回 ok，但日志 `收到未登记设备的心跳 SN=null`，数据库永远不更新 | `HeartbeatPayload.sn` 加 `@JsonProperty("SN")`；并补两条「真实报文绑定」契约测试 |

> 教训：**回 ok 不代表处理成功**。心跳接口刻意吞异常（避免设备重试），所以必须靠日志与数据库断言，不能只看 HTTP 200。

### 复现命令

```powershell
# 1) 建库（一次）
$env:MYSQL_PWD='<密码>'
mysql --user=root --host=127.0.0.1 --port=3306 --execute="CREATE DATABASE IF NOT EXISTS fangke DEFAULT CHARACTER SET utf8mb4;"

# 2) 启动
$env:DB_USER='root'; $env:DB_PASSWORD='<密码>'; $env:DB_HOST='127.0.0.1'; $env:DB_NAME='fangke'
java -jar target\visitor-server-0.1.0-SNAPSHOT.jar

# 3) 心跳 / 记录（真实报文）
curl -s -X POST http://127.0.0.1:8080/api/device/callback/heartbeat -H "Content-Type: application/json" --data-binary "@heartbeat.json"
```

> **不要把数据库口令写进任何提交文件**：本项目通过环境变量注入，`application.yml` 里只有占位符。
### MinIO 照片链路验证（2026-11-16，真实 MinIO）

设备报文里的 `face_base64` 不再进 MySQL，改走「剥离 → 异步上传 → 只存 key」。

| # | 场景 | 结果 |
|---|---|---|
| 1 | 服务启动连 MinIO | 日志 `对象存储就绪 endpoint=http://127.0.0.1:9000 bucket=fangke pathStyle=true`；bucket 自动创建 |
| 2 | 上报带真实 JPEG 的通行记录（634 字节） | ack `{"message":"ok","result":0}` |
| 3 | `access_event.raw` | 长度 **242**，`raw LIKE '%face_base64%'` = **0**（照片已剥离，其余字段保留） |
| 4 | `access_event_media` | 1 行：`event/2026/11/16/T77R781116ZS/evt-minio-001-capture.jpg`，`expire_at = +30 天` |
| 5 | 预签名 URL | `GET /api/admin/access/event/3/media` 返回 path-style URL（`/fangke/event/...`，`X-Amz-Expires=300`） |
| 6 | 按 URL 下载 | HTTP **200**，**634 字节**，magic `FF D8 FF`（JPEG），与上传内容一致 |

> 验证第 5 步需要一个 JWT：用开发密钥 `dev-only-change-me` 按 HS256 签发即可（正式环境走登录接口，P2 提供）。
>
> **MinIO 必须 `path-style`**：否则预签名 URL 会拼成 `http://bucket.host:9000/...` 而无法解析。

### 签到签离 / 对账验证（2026-11-16，真实设备 + MySQL）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 入口设备上报 `inout=1`（张三 13:05） | ack ok；`visit_record` 生成（`checkin_at=13:05`、`status=1 在访`）；预约单 → `40 已签到` |
| 2 | 出口设备上报 `inout=0`（13:40） | `checkout_at=13:40`、`checkout_type=1 设备识别`、`status=2 已离场`；预约单 → `50 已签离` |
| 3 | 手动对账 `POST /api/admin/device/1/resync` | 期望 1 人、设备实际 1 人（`10001`）；`missing=[V202611160001]`、`extra=[10001]`、**removed=1** |
| 4 | `person_device_state` | `V202611160001` expect=1/actual=2/diff=1（待补下发）；`10001` expect=2/actual=2/diff=0（已清理） |
| 5 | 再次对账 | 设备实际 0 人、`extra=[]`、`removed=0`（删除已生效且幂等） |

> **限制（P1 已知）**：对账发现"设备缺失"时只记录并告警，**不会自动补推**——补推需要明文身份证号，而访客档案表在 M4 才建。
> M4 完成后把 `ExpectedPersonPort` 换成带证件号的实现即可自动补推。
>
> **注意**：首次对账把测试机上的历史人员 `10001` 当成"多余"删除（该设备为测试机）。生产上线前应先做一次"设备存量导入"，避免误删。

### M2 员工管理验证（2026-11-16，真实 MySQL + MinIO）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 新增员工（工号 000123 / 手机 / 证件号） | `personNo=E000123`；返回体只有 `138****8000`、`110101********4477` |
| 2 | 库中实际存储 | `phone_enc` 40B、`id_card_enc` 47B、`*_hmac` 64 字符 —— **无明文** |
| 3 | 预签名直传 `POST /employee/1/face/presign` | 返回 path-style PUT URL（`X-Amz-Expires=300`）；PUT 到 MinIO → **HTTP 200** |
| 4 | `POST /employee/1/face/by-key` | 校验 500×600 通过；上传压缩件 `emp-face-std/...`；`employee.face_status=1` |
| 5 | 列表接口 | `faceStatusText=已上传`、`canPassByFace=true`、`deptName=信息部`，证件号仍脱敏 |
| 6 | 表单上传替换照片（520×620） | 新增 1 条 face 记录；旧记录 `is_current=0`，历史保留（BR-E-005） |
| 7 | Excel 导入（2 行） | `total=2, success=1, errors=[{row:3,"手机号格式不正确：12345"}]` |
| 8 | 同一文件重复导入 | `success=0`，第 2 行报「工号已存在：000201」——唯一约束生效 |
| 9 | 停用员工 | `status=3`；`canPassByFace=false` |

> **已知限制**：照片校验只做「大小 / 分辨率 / 可解码」，**人脸数量检测未实现**（需要人脸算法 SDK）。
> 错误码 1008 已保留，接入算法后在 `PhotoValidator#validate` 补一行即可。
>
> 证件号校验位是**真校验**：测试时用 `110101199001011234` 会被拒（校验位不对），合法样例见测试代码里的 `110101199003074477`。

### M4 访客与预约验证（2026-11-16，真实 MySQL）

| # | 场景 | 结果 |
|---|---|---|
| 1 | `POST /api/app/privacy/agree` | 建访客档 `V202611160001`，`visitor_consent` 落 1 条（type=1, v1.0） |
| 2 | 库中访客档案 | `id_card_enc` 47 字节密文、`id_card_mask=110101********402X`，**无明文** |
| 3 | `POST /api/app/appointment`（自约，带 1 同行人） | `A2026111600001`、status=20「待审批」、`visit_employee_id=1`（按被访人手机号自动匹配） |
| 4 | 同行人落库 | `appointment_mate` 2 行：主访客（is_main=1，带 person_no）+ 钱七（is_main=0） |
| 5 | 重复提交 | `{"code":3002,"message":"该访客已有进行中的预约：A2026111600001"}` |
| 6 | `POST .../cancel` | status → **80 已撤回**，`cancel_reason=临时有事` |
| 7 | 员工代邀（`/appointment/invite?employeeId=1`） | `A2026111600002`、status=**30 已通过**（一级自动通过）、`canCancel=false` |
| 8 | 黑名单拦截 | 库中插入黑名单（按 HMAC）后提交 → `{"code":3003,"message":"该人员已被列入黑名单，请联系门卫"}` |
| 9 | 列表 / 详情 | 只返回脱敏值；`statusText`、`mateModeText` 已是中文，前端可直接展示 |
| 10 | 非法 JSON 请求体 | 返回 `1002 请求体不是合法 JSON`（原先误报 5001，已修） |

> **路径说明**：`/api/app/**` 当前未鉴权（小程序登录在 P3），仅用于开发联调，**上线前必须接入登录态**。

### M5 审批流验证（2026-11-16，真实 MySQL）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 提交预约（被访人手机号=员工1） | 自动生成一级任务 `task=1, level=1, assignee=1`，预约单 `current_task_id=1` |
| 2 | `GET /api/app/todo/count`（X-Employee-Id: 1） | `{"pending":1}` |
| 3 | 待办列表 / 详情 | 返回访客姓名、脱敏证件号、事由、时段；`canAct=true` |
| 4 | 催办两次 | 第 1 次 `remaining=1`；第 2 次 **3004「催办过于频繁，请 30 分钟后再试」** |
| 5 | 一级通过（单级配置） | `status=30 已通过`、`dispatchStarted=true` |
| 6 | 切到二级配置（候选 [1,2]）后提交新预约 | 生成一级任务 `task=2` |
| 7 | 一级通过并指定 `nextApproverId=2` | 预约单 `25 待二级审批`，新建 `task=3 level=2 assignee=2`，员工2 待办 +1 |
| 8 | 员工2 通过二级 | 预约单 `30 已通过`、`dispatchStarted=true`；两条任务均 `status=2` |
| 9 | 驳回不填意见 | `1001 驳回必须填写原因` |
| 10 | 驳回并填意见 | 预约单 `60 已驳回`、`reject_reason` 入库、任务 `status=3`、其余待办置 `6 已失效` |
| 11 | `approval_action` 流水 | 催办1条、通过3条、驳回1条，动作全部留痕 |
| 12 | 配置校验 | 开启二级但候选池为空 → `1002 开启二级审批时必须配置候选审批人` |

> **身份说明**：审批接口用请求头 `X-Employee-Id` 模拟当前登录员工（P3 接微信登录态后替换）。
> 待办角标是唯一可靠通知通道（BR-N-002），所以 `/todo/count` 必须保持轻量（单条 COUNT 查询）。

### M6 授权下发验证（2026-11-16，真实设备 + MySQL）

测试机为入口设备 `192.168.10.135`（出口设备是占位记录，已 `status=0` 停用）。

| # | 场景 | 结果 |
|---|---|---|
| 1 | 审批通过 → 自动下发（异步） | `sync_batch` 生成、`total=1 success=1`，预约单 `35 可通行`、`granted_at` 有值 |
| 2 | 授权有效期 | `2026-11-24 00:00:00 ~ 2026-11-24 23:59:59`（按自然日整段，C-60） |
| 3 | **真机名单** | `getAllDeviceIdWhiteList` 返回 `["V202611160006"]` —— 人确实进设备了 |
| 4 | 撤销授权 | 批次 `success=1`；`access_grant.status=4` 且有 `revoked_at`；预约单 `90 已撤销` |
| 5 | 撤销后真机名单 | `idList: null` —— 已从设备删除 |
| 6 | 手动重下 `POST /api/admin/dispatch/{no}` | 不重复建授权，仅新增批次并重推（幂等） |

### 现场踩到的两个真问题（已修）

| 问题 | 现象 | 根因与修复 |
|---|---|---|
| **下发改成异步后读到旧状态** | 审批通过后日志报「预约单当前状态不能下发授权」，`grant` 不生成 | 审批事务未提交，异步线程查到的还是 `20 待审批`。改为 `TransactionSynchronizationManager` **提交后再下发**，并补了一条防竞态单测 |
| **`usertype=visitor` 设备不落库** | `addDeviceWhiteList` 返回「添加成功」，但 `getAllDeviceIdWhiteList` 查不到；换 `white` 立即出现 | 固件行为与协议文档不一致。新增配置 `fangke.device.visitor-user-type`，默认 `white`；**换设备后必须重新验证该取值** |

### M11 保安工作台验证（2026-11-16，真实设备 + MySQL）

| # | 场景 | 结果 |
|---|---|---|
| 1 | `GET /api/guard/dashboard`（GUARD 角色） | `onsite=0 pendingApproval=0 devicesOnline=1/1`，`deviceStatusText=全部在线` |
| 2 | 用 VISITOR 角色的 token 访问 | `{"code":2002,"message":"需要保安或管理员角色"}` |
| 3 | 现场自助登记（source=3） | 预约单 `A2026111600008`，`待审批` |
| 4 | `GET /api/guard/appointment/pending` | 返回该单，`sourceText=现场自助登记`，证件号脱敏 |
| 5 | 催办两次 | 第 1 次 `remaining=1`；第 2 次 **3004「催办过于频繁」**（复用审批模块限流） |
| 6 | 未授权就开门 | **3004「该访客未授权（当前：待审批），不能开门」** |
| 7 | 审批通过后开门 | `{"opened":true,"devices":1}`，真机收到开门指令；`access_event` 落一条 `source=3`、`identify_type=9`（人工开门） |
| 8 | 设备上报进场 → 在访列表 | `visit_record` 生成；`GET /api/guard/visit/onsite` 返回该访客，`overdue=false` |
| 9 | 手工签离 | 预约单 `50 已签离`；`visit_record.checkout_type=2`（保安手工）、`status=2` |
| 10 | 重复签离 | **3015「该人员当前不在访，无法签离」** |

> **权限边界**：保安工作台不提供任何审批接口（通过/驳回），只有查看、催办、开门、签离。
> 人工开门必须满足两者之一：访客已授权且授权在有效期内，或员工代开门时填写人工核验说明。

### 管理端前端联调（2026-11-16，真实后端 + MySQL + MinIO）

前端 `../web/`（Vue3 + TS + Vite + Element Plus）逐页对真实后端验证：

| # | 页面 | 验证内容 | 结果 |
|---|---|---|---|
| 1 | 登录 | `admin` 登录、跳转、token 持久化 | 通过 |
| 2 | 保安工作台 | 看板数字（在访/待审/设备 1/1 在线）、在访列表、待审批页签 | 通过 |
| 3 | 设备管理 | 列表、实时读参数、屏幕提示、对账 | 通过 |
| 4 | 员工管理 | 列表脱敏、新增（证件号校验位真校验）、照片上传（真 MinIO）、照片历史、停用 | 通过 |
| 5 | 预约查询 | 状态筛选、日期筛选、详情、重下、撤销授权按钮态 | 通过 |
| 6 | 通行记录 | 近 7 天默认区间、结果筛选、抓拍照片（MinIO 预签名 URL 直出） | 通过 |
| 7 | 下发批次 | 批次列表、行点击看明细 | 通过 |
| 8 | 审批配置 | 级数、默认审批人、二级候选、催办参数 | 通过 |

联调中暴露并修复的两个后端缺陷：

| 问题 | 现象 | 根因与修复 |
|---|---|---|
| **照片历史接口 500** | `GET /api/admin/employee/{id}/face/history` 报 `5001`，日志 `ClassCastException: BigInteger cannot be cast to Long` | `employee_face.uploaded_by` 是 `BIGINT UNSIGNED`，MySQL 驱动返回 `BigInteger`，RowMapper 里 `(Long) rs.getObject(...)` 直接炸。改为 `getLong/getInt + wasNull` 读取（`JdbcEmployeeFaceRepository`） |
| **时间字段是 ISO 串** | 批次列表显示 `2026-11-16T13:45:37.572` | Jackson 默认序列化 `LocalDateTime` 为 ISO。新增 `config/JacksonConfig` 全局统一为 `yyyy-MM-dd HH:mm:ss` |

## 9. 管理端前端（web/）

```bash
cd ../web
npm install
npm run dev        # http://localhost:5173 ，/api 代理到 127.0.0.1:8080
npm run build      # vue-tsc --noEmit + vite build
```

| 项 | 说明 |
|---|---|
| 技术栈 | Vue 3 + TypeScript + Vite + Element Plus + Pinia + vue-router + axios |
| 路由 | hash 模式；未登录跳 `#/login`；保安工作台仅 GUARD/ADMIN 可见 |
| 鉴权 | token 存 `localStorage`，`api/http.ts` 统一加 `Authorization`；401 自动清 token 回到登录页 |
| 错误提示 | 统一拦截器弹 `ElMessage`；已提示过的错误不再抛到控制台 |
| 页面 | 登录、保安工作台、设备管理、员工管理、黑名单、预约查询、通行记录、下发批次、报表统计、账号管理、审计日志、运维中心、审批配置（13 页） |
| 开发代理 | `vite.config.ts` 的 `/api` → `VITE_API_TARGET`（默认 `http://127.0.0.1:8080`） |

### 移动端（uniapp/）：H5 + 微信小程序 + APP

```bash
cd ../uniapp
npm install
npm run dev:h5          # http://localhost:5175 ，/api 代理到 127.0.0.1:8080
npm run build:h5        # dist/build/h5
npm run build:mp-weixin # dist/build/mp-weixin（微信开发者工具导入）
npm run build:app       # dist/build/app（HBuilderX 打包 APK）
```

| 项 | 说明 |
|---|---|
| 技术栈 | uni-app（Vue3 + Vite 5）+ TypeScript；一套代码出三端 |
| 身份 | 访客 `APP_VISITOR` / 员工 `APP_EMPLOYEE`（手机验证码）；管理员/保安（账号密码） |
| 页面 | 14 个：登录、我的预约、预约详情+通行码、新预约、员工待办、审批详情、消息、我的 + 管理端 6 个（工作台/预约/通行/设备/黑名单/报表） |
| 通行码 | **后端生成 PNG（`qrImage`，Base64）**，三端只放 `<image>`；60 秒倒计时 + 过期自动换新码 |
| 鉴权 | JWT 存 `uni.setStorageSync`，401 自动回登录；管理端接口集中要求 ADMIN |
| 后端地址 | H5 走 `/api` 代理；小程序/APP 构建时用 `VITE_API_BASE` 指定绝对地址 |
| 打包 | 小程序需客户 AppID + 备案；APK 需 HBuilderX + Android 签名证书，详见 [docs/13](../docs/13-移动端与打包指南.md) |

## 10. 换机预埋验证（2026-11-16，真实设备 + MOCK 通道）

| # | 场景 | 结果 |
|---|---|---|
| 1 | `GET /api/admin/device/1/capabilities`（真机） | 14 项能力全部 `supported=true`（LAN 适配器声明，设备档案无覆盖） |
| 2 | `POST /api/admin/device/1/selfcheck`（真机） | `status=OK`：能力齐备、通道 LanHttpAdapter、`model=C978GH`、参数 `platformEnable/heartBeatEnable/heartBeatIp/platformIp/inout` 齐全、设备 1 人 |
| 3 | 换 `channel=MOCK` 后自检 | `status=OK`，适配器 `MockDeviceAdapter`，`model=MOCK-DEVICE`，无真机也全绿 |
| 4 | MOCK 通道远程开门 / 屏幕提示 | `{"opened":true,"devices":1}`、`code=0`，只打日志不碰硬件 |
| 5 | MOCK 通道重下授权 | 批次 `B20261116143801875`，`success=1`，名单写入内存 |
| 6 | `device.capability={"disable":["SCREEN_MESSAGE"]}` 后调屏幕提示 | **HTTP 501 + `{"code":4006,...}`**，提示含型号 `C978GH`、通道与「屏幕提示」 |
| 7 | 清除覆盖后再调屏幕提示 | `code=0`，真机收到提示 |
| 8 | 设备离线时 `selfcheck` | 逐项 `FAIL`（params/version/persons），`detail` 带原因，接口不抛 500 |

> 顺带修掉一个长期问题：`DeviceException` 之前没有 `@ExceptionHandler`，设备离线/口令错在管理端一律显示 5001「系统内部错误」；
> 现在 4001→503、4002→502、4006→501，`message` 直接是可读原因。

## 11. 账号 / 审计 / 报表验证（2026-11-16，真实 MySQL + 浏览器）

| # | 场景 | 结果 |
|---|---|---|
| 1 | `GET /api/admin/role` | 返回 `ADMIN / VISITOR_ADMIN / GUARD` 三个角色 |
| 2 | `POST /api/admin/user` 新建保安账号 | `id=2`；重复账号 → 400「账号已存在」；弱口令 → 400「密码必须同时包含字母和数字」 |
| 3 | 新账号登录 | `roles=GUARD` |
| 4 | 保安 token 访问 `/admin/user`、`/admin/report/overview` | **403 `2002` 需要管理员权限** |
| 5 | 管理员重置密码 | 新密码可登录、旧密码 → 401 |
| 6 | 本人改密码（`/user/me/password`） | 旧密码错 → 400「原密码不正确」；正确 → 新密码可登录 |
| 7 | 停用账号后登录 | **403 `2003` 账号被停用**；重新启用后恢复 |
| 8 | 管理员停用自己 | **409 `3004` 不能停用当前登录的账号** |
| 9 | 审计日志 | 共 51 条，最新一条 `admin / ACCOUNT / RESET_PASSWORD / 127.0.0.1 / 成功`（修好操作人字段后写入） |
| 10 | 审计筛选项 | 模块 7 类、动作 16 类；`?module=ACCOUNT` 命中 10 条 |
| 11 | 报表概览 | `appointments=1 approved=1 checkins=2 events=6 eventPassRate=1.0 avgApprovalMinutes=0.1` |
| 12 | 报表逐日 | 30 行（区间内每天一行，无数据补 0） |
| 13 | 报表区间超一年 | 400（`1002`） |

> 顺带修掉审计的**操作人为空**问题：`AuditAspect` 之前写死 `user_name = null`，
> 现在从 JWT 的 `sub` 取（`AuthInterceptor.ATTR_USER_NAME`），审计表能看到是谁操作的。
> 同时去掉了报表里语义不清的「到访率」（到访人次 ÷ 通过数 会 >100%，容易误读）。

## 12. 动态二维码验证（2026-11-16，真实 MySQL + 浏览器）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 给「可通行」预约签发 | 返回 `RV1.T01.<单号>.<exp>.<nonce>.<hmac8>`，`ttl=60` |
| 2 | 给未来日期的预约签发 | **400 `3010` 不在授权时间**（授权按预约当天 00:00–23:59） |
| 3 | 保安端核验（首次） | `allowed=true`，返回访客姓名/脱敏证件/被访人/授权时段 |
| 4 | 同一张码再核验 | `allowed=true`，`use_count=2` —— **BR-V-020 可重复使用** |
| 5 | 撤销后再核验 | `allowed=false`「二维码已撤销」 |
| 6 | 篡改末位签名 | `allowed=false`「二维码无效或已被篡改」 |
| 7 | 数据库 | `qr_ticket` 只有 `code_hash`（64 位），无明文码；`use_count=2` |
| 8 | 留痕 | `access_event` 中 `source=4`、`identify_type=5`，2 条放行 + 1 条拒绝（带原因） |
| 9 | 签到 | `visit_record` 生成（`gate_id` 取自授权），预约单转 `40 已签到` |
| 10 | 浏览器 | 预约查询「生成通行码」弹出真实二维码图片 + 倒计时；保安工作台「扫码核验」输入码即返回放行结果 |

> **联调中纠正的一处需求偏差**：最初把码实现成「一次性消费」，与 BR-V-020「60 秒内可重复使用、通行后不作废」冲突；
> 已改为累加 `use_count`（V9 迁移重写 `qr_ticket`，同时改为只存 `code_hash`），并补了「同码两次核验都放行」的单测。

## 13. 访客端/员工端 H5 验证（2026-11-16，真实 MySQL + 浏览器）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 员工手机号取码 | `phoneMask=138****1234`，开发模式回显 6 位码，`ttl=300` |
| 2 | 员工登录 | `role=APP_EMPLOYEE`、`subjectId=4`、`name` 正确 |
| 3 | `GET /api/app/me` | 返回姓名/人员编号/部门/职务 |
| 4 | 待办角标 / 列表 | `pending=1`，列表带单号、访客、事由、时段 |
| 5 | 无 token 访问 `/api/app/approval/todo` | **401 请先登录** |
| 6 | App 内审批通过 | `status=30 已通过`、`dispatchStarted=true`；3 秒后预约转 `35 可通行` |
| 7 | 访客登录 | `role=APP_VISITOR`、`subjectId=8` |
| 8 | 我的预约 | `total=1`，状态「可通行」 |
| 9 | 访客取通行码 | `RV1.T01.…`，`ttl=60`；保安端核验 `allowed=true`，`useCount=1` |
| 10 | 浏览器走查 | 登录（验证码自动填）→ 我的预约 → 详情取码（PNG + 倒计时 + 过期自动刷新）→ 员工待办 → 我的 |

移动端暴露并修掉的一个问题：

| 问题 | 现象 | 修复 |
|---|---|---|
| **H5 端口 5174 被 CORS 拒绝** | `POST /api/app/auth/code` 返回 403「Invalid CORS request」 | 浏览器对 POST 会带 `Origin`，后端 `fangke.cors-allowed-origins` 只放了 5173。已把 5174 加入默认允许列表（生产同域部署则不受影响） |

> 测试数据（H5E2E 员工、H5测试访客、预约 A2026111600009、票据/授权/审批/签到记录）已全部清理，
> 并先调「撤销授权」把该访客从真机名单移除，再删库；设备名单人数已复原。

## 14. 通知中心 / 定时任务验证（2026-11-16，真实 MySQL + 浏览器）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 访客提交预约（触发待办） | `notification` 落 2 条：站内 + 短信，`APPROVAL_TODO`，均 `已发` |
| 2 | 员工 App 消息列表 | `total=1`（**只返回站内信**），`unread=1`；短信副本不展示 |
| 3 | 标记已读 / 全部已读 | 未读数 2 → 1 → 0 |
| 4 | App 审批通过 | 访客收到 `APPROVAL_PASSED`（站内 + 短信各 1 条） |
| 5 | 管理端通知记录 | 5 条可见（含渠道、模板、状态、重试次数、失败原因展开） |
| 6 | 定时任务自动执行 | `NOTIFICATION_RETRY` 每分钟写入 `job_run_log`（无需人工触发） |
| 7 | 手动执行「预约过期」 | `affected=1`，预约转 `70 已过期`，访客收到 `APPOINTMENT_EXPIRED`（按设计仅站内） |
| 8 | 任务字典 / 手动执行 | 8 个任务编码齐全；`RECONCILE`（affected=1）、`GRANT_CLEANUP`、`QR_CLEANUP` 均成功并留日志 |
| 9 | 浏览器走查 | 运维中心两个页签（任务日志 8 条 / 通知记录 5 条）；H5 底部「消息 1」角标 → 消息页内容正确 |
| 10 | 数据清理 | 通知、任务日志、测试预约/员工/访客全部清空，基线回到 9 预约 / 2 员工 / 7 访客 |

联调中修掉的一个展示问题：

| 问题 | 现象 | 修复 |
|---|---|---|
| **短信副本重复展示** | 移动端消息列表把「站内 + 短信」两条同内容消息都列出来，用户以为发了两遍 | 移动端查询固定 `channel=1`（站内）；短信记录只在管理端「通知记录」排查。未读数、已读标记同样只针对站内 |
