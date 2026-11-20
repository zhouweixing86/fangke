# 新终端对接：HTTP 客户端（轮询）版

> 版本 v1.0 ｜ 2026-11-19
> 设备：**Linux 人脸识别终端**（准备采购，两三天到货）
> 协议来源（客户提供三份，实际是同一款设备的两种通信方式）：
> - `Linux人脸识别终端对接协议说明书（Http通讯协议，客户端版）-2024-10-01R1`
> - `Linux人脸识别终端对接协议说明书（MQTT通讯协议版-20250102.V1.6）`
> - 在线文档：https://doc.cpyht.com/web/#/231/4545
>
> **本期选定 HTTP 客户端版**：不需要 MQTT Broker，内网单门点最省事，网关/端口与现有服务一致。
> MQTT 版仅作对照（见 §9），暂不实现。

---

## 1. 一句话说清区别

| 维度 | 九鼎 YC-P208（**已下线，代码/文档已删除**） | **新终端（HTTP 客户端版，唯一在用）** |
|---|---|---|
| 谁主动 | **平台**连设备的 8091 端口 | **设备**连平台的 HTTP 接口 |
| 下发名单 | 平台直接 POST `addDeviceWhiteList` | 平台**入队**，设备心跳来取 `action`，再回来拉详情 |
| 读设备状态 | 平台可随时读参数/名单 | **做不到**（协议里没有"平台问设备"的接口） |
| 远程开门 | 平台直接调接口，实时 | 入队 → 下一个心跳生效（默认 30 秒，可配） |
| 通行记录 | 设备 POST 给平台 | 设备 POST 给平台（一样） |
| 二维码核验 | 设备回调 `/compare/online` | 设备把码推给平台（`thirdQrUrl`），平台回 `openGate` |
| 签名 | body 里带 `password` | 顶层字段 MD5 签名（`sign`） |
| 人员唯一键 | 平台 `employee_number`（personNo） | **`idno` = 身份证号**（没有则用 personNo） |

> 结论：**业务代码零改动**。适配层新增一个 `OpenApiPullAdapter`（通道 `PULL`），
> 平台侧的"下发名单/开门/校时"变成入队，其余（预约、审批、二维码、签到签离、报表）完全复用。

---

## 2. 平台提供的接口（设备调用）

所有地址前缀：`http://<平台IP>:8080/openapi/v1/`，`POST`，`Content-Type: application/json`。

| 端点 | 谁调用 | 作用 | 平台实现 |
|---|---|---|---|
| `/heartBeat` | 设备 | 心跳，响应里带 `action` 指挥设备 | `DeviceOpenApiController#heartbeat` |
| `/getAllDeviceWhiteList` | 设备 | 拉平台全量名单索引 `[{idno,personVer}]` | `#allDeviceWhiteList` |
| `/addDevicWhiteInfo` | 设备 | 取"要新增的人" `[{idno,personVer}]` | `#addDeviceWhiteInfo`（读队列） |
| `/updateDeviceWhiteInfo` | 设备 | 取"要更新的人" | `#updateDeviceWhiteInfo`（读队列） |
| `/delDevicWhiteList` | 设备 | 取"要删除的人" `[{idno}]` | `#delDeviceWhiteList`（读队列） |
| `/getDevicWhiteInfo` | 设备 | 取单人详情（含照片 base64） | `#deviceWhiteInfo` |
| `/syncWhiteResult` | 设备 | 回报增/改/删结果 | `#syncWhiteResult`（出队） |
| `/getDeviceWhiteListCount` | 设备 | 名单总数 | `#whiteListCount` |
| `/syncDeviceTime` | 设备 | 取当前时间 | `#syncDeviceTime` |
| `/syncDeviceParameter` | 设备 | 取平台参数 | `#syncDeviceParameter` |
| `/uploadDetectRecord` | 设备 | **通行记录推送**（含抓拍） | `#uploadDetectRecord` |
| `/qrCodePush` | 设备 | **二维码推送**，平台判定是否开闸 | `#qrCodePush` |
| `/checkPwd` | 设备 | 密码开门（默认关闭） | `#checkPwd` |

响应统一：`{"code":0,"msg":"成功","data":{...}}`；`code!=0` 表示失败。
**注意**：不能套平台的 `ApiResponse`（会被设备当失败重试）。

### 2.1 名单回执字段取值（真机联调必看）

`POST /syncWhiteResult` 的请求体：

```json
{ "deviceno": "T77R781116ZS", "data": { "idno": "1101...", "resulttype": 0, "resultcode": 0, "msg": "ok" } }
```

| 字段 | 取值 | 含义 |
|---|---|---|
| `idno` | 身份证号（无则 personNo） | 人员唯一键，平台据此反查 personNo |
| `resulttype` | **0 = 新增 / 1 = 更新 / 2 = 删除** | 决定完成哪条队列指令；发错会「更新队列 0 条」 |
| `resultcode` | **0 = 成功 / 非 0 = 失败** | 失败置为失败并记录 `msg`，超过重试上限不再下发 |
| `msg` | 文本 | 失败原因，写入 `device_cmd.error_msg` 与日志 |

> 若厂商文档的 `resulttype` 取值与此不同（例如 1=新增），必须在
> `OpenApiDeviceService#syncWhiteResult` 的 `switch` 里做映射，否则回执对不上号。

---

## 3. 签名

```
sign = MD5( 除 data/sign 外所有顶层字段按字段名ASCII升序拼 key=value&… + "&key=" + 平台key ).toUpperCase()
```

- `data` **不参与**签名（大字段，避免拼接开销）；
- 值用原始值，**不做 URLEncode**；
- 实现：`device/pull/OpenApiSign.java`，校验失败会打印"期望签名 + 参与字段 + 原始报文"，便于现场核对；
- 平台 key 与设备侧 `thirdHttpkey` 必须一致：`fangke.device.pull-sign-key`（或 `DEVICE_PULL_SIGN_KEY`）；
- 联调期若对不上，可临时 `DEVICE_PULL_VERIFY_SIGN=false` 抓包，**生产必须 true**。

> 文档 §2.3 的示例串有笔误（`driverno`、`ver =v1.0` 带空格）。我们按上面通用规则实现，
> 真机第一次心跳时用日志里的"期望签名"与设备实际 `sign` 比对，一处即可定位差异。

---

## 4. 名单同步流程（核心）

```
① 平台审批通过 → DispatchService 下发 → OpenApiPullAdapter.upsertPerson
                        ↓
                  device_cmd 入队 ADD_PERSON(personNo)
                        ↓
② 设备心跳 /heartBeat  → 平台回 {"action":"addDevicWhiteInfo","heartTime":30}
                        ↓
③ 设备 POST /addDevicWhiteInfo → 平台返回待新增列表 [{idno, personVer}]（同时标记"执行中"）
                        ↓
④ 设备对每个 idno POST /getDevicWhiteInfo → 平台返回姓名/有效期/照片 base64
                        ↓
⑤ 设备 POST /syncWhiteResult（resultcode=0 成功）→ 平台按人出队
```

- **`personVer`** 暂用 `access_grant.id`：人员内容变更（换照片等）会走 `UPDATE_PERSON` 指令，不靠版本号发现；
- **删人**：`delDevicWhiteList` 直接返回 `[{idno}]`，设备本地删除后回执；
- **设备重启**：走 `getAllDeviceWhiteList` 全量比对，本地版本 >= 平台则跳过；
- **去重**：同设备同动作同人只保留一条未完成指令（`JSON_EXTRACT(payload,'$.personNo')`）；
- **超时回收**：指令下发后 `pull-command-timeout-seconds`（默认 300s）未回执 → 重新排队，超过 `max-retry` 置失败并留错误信息。

---

## 5. 参数下发（`syncDeviceParameter`）

协议里**没有**"平台读设备参数"的接口，所以：

| 动作 | 新终端 | 说明 |
|---|---|---|
| 写参数 | ✅ 入队 `PARAM_SYNC` → 设备来取 | 返回平台期望值（见下表） |
| 读参数 | ❌ 不支持 | 管理端点"参数"会返回 4006 并说明原因 |

平台返回的关键参数：

| 字段 | 值 | 原因 |
|---|---|---|
| `inout` | 设备档案的 `direction` | 入口/出口 |
| `thirdOpenDoorLocal` | `0` | **关键**：让设备把二维码交给平台判定，而不是本地判 |
| `thirdQrUrl` | `{pull-public-base-url}/openapi/v1/qrCodePush` | 不配这个，二维码就走不到平台 |
| `recogModeDB/IC/ID` | `1` | 启用人脸/刷卡/人证 |

> `thirdHttpUrl`/`thirdHttpkey` 也可远程改，但**先手工在设备上配好指向本平台**，避免远程改错后失联。

---

## 6. 记录推送字段映射

`uploadDetectRecord.data` → `access_event`：

| 设备字段 | 平台字段 | 说明 |
|---|---|---|
| `time` | `event_time` | `yyyy-MM-dd HH:mm:ss`，解析失败用服务器时间 |
| `inout` | `direction` | 1 进 / 0 出，**不推断** |
| `openType` | `identify_type` | 见下表 |
| `resultStatus` | `result_status` | 1 成功 / 0 失败，失败写 `fail_reason` |
| `idNum` | `id_card_mask` | **明文进来立刻脱敏**，不落明文 |
| `name` | `person_name` | |
| `idNum` → 查访客档案 | `person_no` | 命中访客档案才有值；没有则留空（只留痕，不签到） |
| `scenePhoto` | 对象存储 + `access_event_media` | 异步上传，不阻塞 ack |
| `tableID` / `id` | `event_uuid` | **幂等键**，设备重推不会重复入库 |
| `Temp` | `temperature` | |

`openType → identify_type`：

| openType | 含义 | identify_type |
|---|---|---|
| 0 / 1 | 人脸（陌生人 / 白名单） | 0 人脸 |
| 2 / 9 | 身份证 / 身份证人证库 | 2 人证 |
| 3 / 10 | IC 卡 / IC 人卡库 | 3 IC 卡 |
| 4 / 7 / 8 | 远程开门 / 拨号 / 物业管理处 | 9 人工开门 |
| 5 | 二维码 | 5 二维码 |
| 6 | 密码 | 6 密码（新增编码，见 docs/10） |
| 其他 | 未知 | 0 人脸（兜底，不抛异常） |

---

## 7. 二维码开门（推荐路径）

设备把扫到的码原样推给平台，平台判定后回 `openGate`：

```json
// 设备 → 平台
{"actionName":"qrCodePush","data":"{\"qrCode\":\"RV1.T01.A2026...\"}","deviceno":"...","version":"v2.0"}
// 平台 → 设备
{"actionName":"qrCodePush","resultcode":"1","message":"核验通过，允许通行","openGate":1}
```

平台侧复用 M7 的完整判定（`QrTicketService#verify`）：验签 → 票据 → 时间窗 → 授权 → 计数 → 留痕 → 签到。
**好处**：撤销授权/作废码立即生效，不用重新下发名单。

> 设备本地还有一套二维码格式（`A1=证件号=opendoor=yyyyMMddHHmm` 再 base64，5 分钟有效）。
> 我们**不用**它：`thirdOpenDoorLocal=0` 时设备只认平台判定。

---

## 8. 能力矩阵（新终端 vs 我们需要的 14 项）

| 能力 | 支持 | 说明 |
|---|---|---|
| `PERSON_UPSERT` | ✅ | 入队 + 设备拉详情 |
| `PERSON_REMOVE` | ✅ | 入队 |
| `DOOR_OPEN` | ✅ | 入队，**延迟 = 心跳间隔** |
| `TIME_SYNC` | ✅ | 入队 |
| `PARAM_WRITE` | ✅ | 入队 |
| `FACE_PHOTO` | ✅ | 员工照片 `picData1`（base64） |
| `QR_ONLINE` | ✅ | `thirdQrUrl` 推送 |
| `HEARTBEAT` / `RECORD_CALLBACK` | ✅ | |
| `PARAM_READ` | ❌ | 协议没有 |
| `PERSON_LIST` | ❌ | 平台无法反查设备名单（对账退化为"平台侧期望"） |
| `VERSION_READ` | ❌ | 协议未定义（MQTT 版才有） |
| `PERSON_CLEAR` | ❌ | 无清空名单指令 |
| `SCREEN_MESSAGE` | ❌ | HTTP 版无（MQTT 版 §3.26 才有） |

不支持的能力在调用时返回 **4006** 并写明原因（不是 500），设备管理页「自检」也能看到。

> **对账影响**：`POST /api/admin/device/{id}/resync` 在 PULL 设备上会失败并提示"不支持读取设备名单"。
> 这是协议限制，不是 bug；替代手段是看 `syncWhiteResult` 回执与 `device_cmd` 队列状态。

---

## 9. 为什么选 HTTP 而不是 MQTT

| 维度 | HTTP 客户端版（已实现） | MQTT 版（未实现） |
|---|---|---|
| 额外组件 | 无 | 需要 EMQX/Mosquitto Broker |
| 远程开门延迟 | 一个心跳周期（默认 30s，可调到 5~10s） | 实时 |
| 平台读设备 | ❌ | ✅（有 `getDeviceVersionInfo` 等） |
| 屏幕提示 | ❌ | ✅（自定义弹框） |
| 二维码核验 | ✅ 推送判定 | ✅ 推送判定 |
| 部署复杂度 | 低（一个 Spring Boot + Nginx） | 中（多一个 Broker + 账号鉴权） |

**结论**：先上 HTTP（够用、零新增组件）；若后续需要"实时开门/屏幕提示"，再补 `MqttDeviceAdapter`
（通道 `CLOUD` 已有占位，主题固定为 `openapi/mqtt/v1/{sn}` 上行、`downapi/mqtt/v1/{sn}` 下行）。

---

## 10. 设备到货后要做的（按顺序）

### 10.1 设备侧配置（屏幕菜单）

| 项 | 值 |
|---|---|
| 平台地址 `thirdHttpUrl` | `http://<平台IP>:8080/openapi/v1` |
| 平台 key `thirdHttpkey` | 与 `DEVICE_PULL_SIGN_KEY` 一致 |
| 二维码开门 | 关闭"本地判断"（`thirdOpenDoorLocal=0`），地址指向 `/openapi/v1/qrCodePush` |
| 心跳间隔 | 30 秒（平台会在 `heartTime` 里再次下发） |
| 记录推送地址 | `http://<平台IP>:8080/openapi/v1/uploadDetectRecord` |

### 10.2 平台侧

```powershell
# 平台对设备可达的地址（下发给设备当 thirdQrUrl）
$env:DEVICE_PULL_PUBLIC_BASE_URL = 'http://192.168.10.50:8080'
$env:DEVICE_PULL_SIGN_KEY = '<与设备 thirdHttpkey 相同>'
```

设备首次心跳会**自动登记**到设备管理（`channel=PULL`，mac 先占位），并且落在**「待分配」门点**。
之后在后台两步补齐：① 用「档案」把设备分配到真实门点（不分配就不会收到该门点的访客名单）；② 补 mac/型号/位置。

### 10.3 必验清单（真机）

| # | 场景 | 期望 |
|---|---|---|
| 1 | 设备首次心跳 | 后台出现设备，`online=1`；日志里 `sign` 校验通过 |
| 2 | 签名故意改错一位 | 平台回 `code=1 签名错误`，设备侧能看到错误 |
| 3 | 审批通过一个人 | `device_cmd` 出现 `ADD_PERSON` → 下个心跳 → 设备名单 +1 |
| 4 | 撤销授权 | `DEL_PERSON` → 设备名单 -1 |
| 5 | 刷身份证进门 | 后台「通行记录」出现记录，`identify_type=2`，证件号已脱敏 |
| 6 | 同一记录设备重推 | 只入库一条（幂等） |
| 7 | 手机刷 M7 通行码 | 设备推 `qrCodePush` → `openGate=1` → 开门 |
| 8 | 过期/撤销的码 | `openGate=0` 并回原因 |
| 9 | 远程开门 | 最迟一个心跳周期内开门；`device_cmd` 立即出队不重放 |
| 10 | 断网 5 分钟后恢复 | 设备补推记录；队列指令重新排队后仍能下发 |

### 10.4 已知待办（不影响上线）

| 项 | 说明 |
|---|---|
| 员工刷脸记录不触发签到 | 记录里只有 `idNum`，目前只按访客档案反查 `person_no`；员工刷脸属于考勤范畴，需要时补员工反查 |
| `SCREEN_MESSAGE` | HTTP 版无此协议；如必须要有，走 MQTT 版 |
| 密码开门 | 已实现 AES 解密与响应，业务侧默认关闭（访客系统无"人员密码"概念） |
| 版本号采集 | 设备型号/固件需在后台手工登记（协议无上报） |
| 实时对账 | 依赖设备回执，无法做三方比对（对账接口已随九鼎移除） |

---

## 11. 代码位置

| 内容 | 文件 |
|---|---|
| 接口入口（13 个端点） | `server/.../device/pull/DeviceOpenApiController.java` |
| 业务逻辑（心跳决策/名单/记录/二维码） | `server/.../device/pull/OpenApiDeviceService.java` |
| 指令队列（复用 `device_cmd`） | `server/.../device/pull/PullCommandRepository.java`、`JdbcPullCommandRepository.java` |
| 签名 | `server/.../device/pull/OpenApiSign.java` |
| 记录字段映射 | `server/.../device/pull/OpenApiRecordMapper.java` |
| 设备适配器（通道 `PULL`） | `server/.../device/adapter/OpenApiPullAdapter.java` |
| 配置项 | `application.yml → fangke.device.pull-*` |
| 单测 | `server/src/test/java/.../device/pull/`（签名 5 + 映射 6 + 服务 8） |

---

## 12. 真机联调实测（YC-P208，2026-11-20）

### 12.1 设备档案

| 项 | 值 |
|---|---|
| 型号 | YC-P208（RV1109 主板，C978GH 系列） |
| 标签 SN | `T77R781116ZS`（仅贴在机器上，协议里**不用**它） |
| **协议编号 `deviceno`** | `2608252243655`（= 设备 `cpuserial`，查询式终端唯一身份，也是 `device.device_sn`） |
| 有线 MAC | `00:1C:BA:65:14:AD`（`getDeviceVersion.macaddr`） |
| WiFi MAC | `54:01:4A:7A:E2:61`（另一张网卡，协议不上报） |
| 固件 | `sysver 2.2.122.B61030001R` / `appver F708003M24F550-TR608` / 算法 `ALG_MG_55_3_V0_1` |
| 人脸容量 | 50000 |
| Web 后台 | `http://<设备IP>/home.html`，admin / 888888 |

> 标签 SN 与协议编号是两套东西。设备管理里按协议编号建档；上表两个值都要记在备注，现场换机才认得出来。

### 12.2 设备后台必填项（平台管理 → 第三方设备管理平台-HTTP）

| 后台字段 | 配置项 | 值 |
|---|---|---|
| 启用第三方平台 | `thirdcloudflag` | 启用（1） |
| 第三方平台地址 | `thirdupdateurl` | `http://<平台IP>:8080` ← **只填根地址，不要带 `/openapi/v1`** |
| 第三方平台Key | `thirdupdatekey` | 与 `fangke.device.pull-sign-key` 一致；联调期平台侧 `pull-verify-sign=false` |
| 设备开机是否同步名单 | `thirdcloudsynclist` | 启用（开机先拉一次全量名单） |

> ⚠️ **最容易踩的坑**：地址填成 `http://192.168.0.103:8080/openapi/v1` 时，设备会在后面再拼一次 `/openapi/v1/heartBeat`，实际请求变成 `/openapi/v1/openapi/v1/heartBeat`。早期那一串 500 就是这个原因，不要怀疑网络。

### 12.3 实测协议行为（与文档的差异）

| 项 | 文档描述 | 真机实测 |
|---|---|---|
| 心跳请求体 | `ver/deviceno/timestamp/sign/action` | 一致：`{"action":"heartBeat","deviceno":"2608252243655","sign":"…","timestamp":"20261120201834213","ver":"v1.0"}` |
| 心跳间隔 | 由平台响应 `heartTime` 决定 | 正常（200）时 **30 秒**一次；连续失败时 **6 秒**重试；恢复后自动回到 30 秒 |
| 开机动作 | 未说明 | 先 `getAllDeviceWhiteList`，再进心跳循环 |
| 名单下发 | 未细化时序 | `heartBeat`（响应带 action）→ `addDevicWhiteInfo`（批量）→ 逐人 `getDevicWhiteInfo` → `syncWhiteResult` 回执 |
| `mac` | 心跳带 `mac` | **不带**。平台侧 `device.mac` 自动填占位符 `PULL-<deviceno>`，要真机 MAC 需人工补 |
| 型号 / 固件 | — | 心跳不上报，平台侧需手工登记（或读设备 `/getDeviceVersion`） |

实测时序（2026-11-20 20:21:06，一键同步员工名单之后）：

```
POST /openapi/v1/heartBeat          200  89B   ← 响应带 actionPara
POST /openapi/v1/addDevicWhiteInfo  200 317B   ← 批量 6 人
POST /openapi/v1/getDevicWhiteInfo  200 277B   ×6
POST /openapi/v1/syncWhiteResult    200  36B   ×6
POST /openapi/v1/heartBeat          200  76B   ← 已无指令
```

设备本体 `getDeviceVersion.curCount` 从 `0` 变成 `6`，确认名单真的写进终端，不是只在平台上"假成功"。

### 12.4 现场排障口诀

1. **先验平台对局域网是否可达**：手机浏览器打开 `http://<平台IP>:8080/health`，应返回 `{"code":0,…}`。这一步能立刻分清"平台不可达"和"设备不发"。
2. **再看设备到底有没有发**：Tomcat access log 是唯一可靠手段（启动参数 `--server.tomcat.accesslog.enabled=true --server.tomcat.accesslog.buffered=false`），含 404/500 全量记录。没有日志 = 请求根本没到，别再去查代码。
3. **未匹配路径必须返回 404**：之前根路径和未知路径都被兜底异常处理成 500，设备据此判定"平台故障"并停发心跳。这是本次卡了近一小时的隐藏原因。
4. **心跳线程只在开机和"保存平台配置"时重建**：改完配置最好重启设备。
5. 设备 Web 后台 API 速查（脚本化联调用）：
   - `POST /deviceLogin` `{username, password: md5(明文)}` → `token`
   - `POST /getconnectCloudflag` / `POST /setconnectCloudflag` `{token, data}`（`data` 为整份配置对象，改一个字段要回传全量）
   - `POST /getDeviceVersion` `{token}` → `curCount`（本地人数）、`runInfo`（运行时长 / WiFi 信号 / IP）
   - `POST /setDeviceReboot` `{token}` → 重启

### 12.5 平台侧配套改动

| 改动 | 位置 |
|---|---|
| 新增 `GET /` 与 `GET /health` 连通性探针（原来根路径会 500） | `server/.../common/PlatformRootController.java` |
| `NoResourceFoundException` → 404、`HttpRequestMethodNotSupportedException` → 405，不再误报 500 | `server/.../common/GlobalExceptionHandler.java` |
| 新增错误码 `1009 接口不存在` | `server/.../common/ErrorCode.java` |

### 12.6 三套文档对应三种接入方式（不要混用）

| 文档 | 模型 | 本项目的用法 |
|---|---|---|
| 桌面《HTTP 通讯协议，客户端版》 | **设备主动轮询平台**（`/openapi/v1/heartBeat`、`getAllDeviceWhiteList`、`uploadDetectRecord`…） | ✅ **本项目采用这一套**，`DeviceOpenApiController` 的 13 个端点 |
| 桌面《MQTT 通讯协议版》 | 设备与平台走 MQTT（`openapi/mqtt/v1/{sn}` 上行 / `downapi/mqtt/v1/{sn}` 下行） | ❌ 未实现；设备后台有「第三方设备管理平台-MQTT」页签，可留作备选通道 |
| 文档站 item 231「人脸-终端-HTTP-RV1109」 | **设备自身是服务端**（9090 端口）：设备把心跳/识别记录/二维码/密码开门**回调**给平台，平台反过来调设备的登录/参数/开闸/抓拍/重启等下行接口 | ⏸ 仅作补充手段。设备后台对应的是「第三方平台记录上传」页签（`updaterecord` / `hearturl` / `updateurl`）；现场需要远程抓拍、弹框、改网络时才会用到 |

> 结论：让设备"接入访客系统"只需 12.2 那一页的配置；item 231 的下行接口属于增强能力，不影响通行链路。

### 12.7 本机联调启动命令（供复现）

```powershell
cd D:\fangke\server
$env:DB_USER='root'; $env:DB_PASSWORD='<本机 MySQL 口令>'
$env:DEVICE_PULL_VERIFY_SIGN='false'          # 联调期跳过设备签名校验
$env:DEVICE_PULL_SIGN_KEY='fangke-pull-key'   # 与设备「第三方平台Key」一致
$env:DEVICE_PULL_PUBLIC_BASE_URL='http://192.168.0.103:8080'
java -jar target\visitor-server-0.1.0-SNAPSHOT.jar `
  --server.tomcat.accesslog.enabled=true --server.tomcat.accesslog.buffered=false `
  --server.tomcat.accesslog.directory=D:/fangke/server/logs `
  --server.tomcat.accesslog.prefix=access --server.tomcat.accesslog.suffix=.log `
  --server.tomcat.accesslog.file-date-format= "--server.tomcat.accesslog.pattern=%t %a %m %U %s %b"
```

> Windows 上 `-Dserver.tomcat.accesslog.enabled=true` 这类参数会被 PowerShell 拆坏（报"找不到主类 .tomcat.accesslog.enabled=true"），改用 `--property=value` 形式。
