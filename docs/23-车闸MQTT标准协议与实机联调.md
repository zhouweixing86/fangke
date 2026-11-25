# 23 车闸 MQTT 标准协议与实机联调

> 目的：把「平台下发车牌 → 相机识别开门 → 平台删除车牌」这条链路钉死在一手协议文档 + 真机验证上。
>
> 本机：`PCM500FSPUTV14`（蜂晶系列），SN `COPCM-E10-LS26050994`，固件 `21.2.0.3045`，APP `21.1.22.15541_20260408`。

---

## 1. 协议文档来源

| 项 | 内容 |
|---|---|
| 文档 | **《信路通路外停车 MQTT 协议接口规范 V14》**（48 页，2026-10-28） |
| 适用范围 | 封面写明适用 **PCM500-FSP 系列**（本机正是该系列） |
| 官方资源页 | https://www.ltfyun.com/download.html （按设备系列筛选，系列 ID `6gf2tqocf3b65f2` = P1S/COPCM） |
| 本地留档 | `private/vendor-docs/MQTT协议V14.pdf` 与抽取的 `MQTT协议V14.txt`（gitignored） |

同系列还能下载：通用 HTTP 协议 V1.3、WebSocket 协议 V4、蜂晶 TCP 2.0、HvDevice SDK v2.2.2.116。

> ⚠️ 注意区分三套东西：
> 1. **本机 HTTP/XML 接口**（`POST /Signalway.fcgi?<Cmd>`）——Web 后台用的，命令是 `AddNameList/GetNameList/SetIP/...`；
> 2. **HvDevice SDK**（TCP 9910）——`HVAPI_AddNameList("京A00000-20261231$")` 等；
> 3. **MQTT 标准协议**（本文）——白名单叫「固定车 CarInfo」，命令是 `AddCarInfo/DeleteCarInfo/...`。
> 三者命令名不通用，别混。

## 2. 信封与主题

公共报文头（上下行一致，字段顺序不固定）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `command` | string | 消息类型 |
| `data` | **string** | 业务 JSON **序列化成字符串**后的值（协议原文加粗要求） |
| `requestId` | string | 协议包 ID；回执会带回同一个值用于关联 |
| `time` | string | `yyyy-MM-dd HH:mm:ss` |
| `version` | string | 本机固定 `1.0.1` |
| `sn` | string | 设备序列号 |

主题：

- 相机发布（上行）：`upload/{ClientID}`，ClientID 默认 `{DevSerialNum}`
- 相机订阅（下行）：`download/{ClientID}`

## 3. 下行命令（平台 → 相机）

| 命令 | 作用 | data |
|---|---|---|
| `AddCarInfo` | 增量增加/更新固定车（有则更新，无则新增） | `{"carInfoList":[{"plate":"京A55555","type":1,"time":"2024-11-13"}]}` |
| `DeleteCarInfo` | 删除固定车（支持多车牌） | `{"plateList":["京A55555","京A66666"]}` |
| `QueryCarInfo` | 查询固定车 | `{"queryId":"...","plate":"京A55555"}` |
| `DlCarInfo` | 全量导入固定车（给文件 URL） | `{"url":"http://.../whitelist.txt"}` |
| `EnableUpdate` | 通知设备做全量更新 | `{"passruler":1,"carinfo":1}` |
| `ClearData` | 清空固定车等数据 | `{"clearCarInfo":1}` |
| `EnableReply` | 使能下行回执（设备 `Conn` 上行后下发） | `{"enable":1}` |
| `Config` | 配置合集（含 `enableReply`/`enableOffline`） | 见协议 §4.4.22 |
| `Open` / `Close` | 开闸 / 关闸（远程开门） | 见协议 §4.4.2/4.4.3 |
| `ResetDevice` | 重启相机 | `""` |

V1 的 `time` 语义：**月租车=到期日期；贵宾车=当前日期**，格式 `yyyy-MM-dd`。

### 3.1 V2 命令（本机固件不支持）

协议 V11 起新增 `AddCarInfoV2` / `QueryCarInfoV2` / `DlCarInfoV2`，`data` 里可带
`plate / type / createTime / startTime / endTime / timeSegEnable / segStart / segEnd / vehicleCode / memo`。

**实测：本机（APP `21.1.22.15541_20260408`）回 `errCode=1, errInfo="Unknow CMD."`**，所以平台默认走 V1。
换新固件时，只需把 `.env` 里的 `VEHICLE_MQTT_ADD_TEMPLATE` 改成 V2 模板即可。

### 3.2 固定车类型（协议 §5.1）

| 值 | 含义 | 实测相机行为 |
|---|---|---|
| 1 | 月租车 | `time` 存成「到期时间」，到期失效 ✅ 访客默认用它 |
| 2 | 产权车 | — |
| 3 | 储值车 | — |
| 4 | 贵宾车 | **到期时间存成「无」= 永不过期**，不适合有有效期的访客 |
| 5 | 黑名单 | — |
| 6/7 | 自定义白/黑名单 | — |
| 9 | 脱机放行表达式（支持 `白*` 通配） | — |

## 4. 上行命令（相机 → 平台）

| 命令 | 说明 |
|---|---|
| `Conn` | 设备上线（含 `deviceType`）；平台收到后下发 `EnableReply` |
| `Rtd` | 心跳，`data={"hasCar":0/1/2}`（0 无车 / 1 有车 / 2 未知） |
| `Result` | **识别结果**（见下） |
| `OfflineResult` | 离线重传的识别结果 |
| `Image`/`SnapshotPic` | 图片 |
| `CarInfo` / `CarInfoV2` | 固定车查询结果 |
| `BarrierStatus`/`IOStatus` | 道闸/IO 状态 |
| 与下行同名的 command | **下行回执**：`data={"errCode":0,"errInfo":""}` |

`Result` 关键字段：`carNO`(车牌) `carNOC`(颜色) `carNOType`(牌型) `confid`(0-1000) `triggT` `imgT`
`openGate` `validPlate`(TRUE/FALSE/UNKNOWN) `encrypted`(已加密/未加密) `guid` `resultType` 等。

> 相机若开了「车牌加密」，`carNO` 是密文（如 `xTF2ejgXJ3ftkvvPdDp7sA==`），平台日志会提示
> 「识别结果的车牌已加密」，此时需要关掉加密或约定密钥。

## 5. 回执机制

1. 平台在收到 `Conn` 后下发 `EnableReply {"enable":1}`（也由 `Config` 重复使能）；
2. 之后每条下行，相机都回一条**同名 command** 的上行：`data={"errCode":0,"errInfo":""}`，
   `requestId` 与下行一致；
3. 平台把 `errCode=0` 记为「下行回执：成功」，非 0 记为失败并显示 `errInfo`。

本机相机配置项 `[CloudSpace\MQTT] EnableReply` 为 0 时不主动回执，靠平台下发使能即可。

## 6. 实机联调记录（2026-11-25 23:39 – 2026-11-26 00:10）

| 时间 | 动作 | 结果 |
|---|---|---|
| 23:39 | 相机经电脑共享上网连上 broker | 5s 一次 `Rtd`（`hasCar:2`）+ `Image`，说明链路与账号都对 |
| 23:55 | `EnableReply` | 回执 `{"errCode":0,"errInfo":""}`，requestId 一致 |
| 23:55 | `AddCarInfoV2`（本机固件） | ❌ `errCode=1, "Unknow CMD."` → 改用 V1 |
| 23:56 | `AddCarInfo`（type 1，time=2026-12-31） | ✅ 回执 0；相机 `GetNameList` 读到 `苏A12345` 月租车，`overdue_time=2026-12-31 23:59:59` |
| 23:57 | `DeleteCarInfo` | ✅ 回执 0；`totalCnt=0` |
| 23:58 | `AddCarInfo` type 4（贵宾车） | ✅ 回执 0，但 `overdue_time=无` → 永不过期，**弃用** |
| 00:06 | 相机改 DHCP + `ResetDevice` | ✅ 自动拿到 `192.168.137.166`，出网 IP 为办公网 `117.89.73.58`，MQTT 重连成功 |
| 00:09 | 平台 `POST /mqtt/plate` → `AddCarInfo` | ✅ 回执 0 → 白名单出现 `苏A12345` |
| 00:09 | 平台 `DELETE /mqtt/plate` → `DeleteCarInfo` | ✅ 回执 0 → `totalCnt=0` |
| 00:04 | 半死连接 | ⚠️ Paho 心跳超时后未自动重连，下发接口卡到 nginx 504；已加看门狗 + 发布前判活 |
| 00:19 | 再次 504，定位根因 | ⚠️ 两次故障都紧跟相机 `Conn` 之后：`Conn` 处理里**在 MQTT 回调线程直接 publish** `EnableReply`，把 Paho 接收线程堵住 → PINGRESP 处理不了 → 30s 后判定 keepalive 超时 → 连接被杀、HTTP 线程一直等 |
| 00:24 | 修复验证 | ✅ `EnableReply` 改到独立线程（`vehicle-mqtt-side`），下发超时 5s + 超时即强制重连；重启相机触发 `Conn` 后无任何 keepalive 超时，下发改/删车牌均回执 `errCode=0` |

## 7. 平台实现映射

| 协议要素 | 代码/配置 |
|---|---|
| 信封与模板 | `VehicleMqttService.DEFAULT_ADD/DELETE/IMPORT`、`AppProperties.Vehicle.Mqtt.*Template`、`application.yml` `fangke.vehicle.mqtt.*` |
| 固定车类型 | `fangke.vehicle.mqtt.plate-type` / `VEHICLE_MQTT_PLATE_TYPE`（默认 1） |
| 回执使能 | `VehicleMqttService.applyConfig`（`Conn` 后自动下发 `Config{enableReply:1, enableOffline:1}`）+ `POST /api/admin/vehicle/mqtt/config`、`/enable-reply` |
| 回执解析 | `describeWithoutPlate`：`errCode=0` → 「下行回执：成功」 |
| 识别结果解析 | `extractPlate`：`data` 是字符串也能解析，字段优先 `carNO` |
| 连接可靠性 | `VehicleMqttGateway.ensureConnected` 看门狗（默认 30s，`fangke.vehicle.mqtt.watchdog-ms`）+ `publish` 前判活 + 发布超时 `publish-timeout-ms`（默认 5s，超时强制重连） |
| 回调线程安全 | **禁止在 `messageArrived` 里直接 publish**：`Conn` 后的 `EnableReply` 走独立线程池 `vehicle-mqtt-side` |
| 日志降噪 | 心跳 `Rtd` 与无车 `Image` **不写** `vehicle_mqtt_log`（5s 一条会撑爆库），只打 debug |
| 管理端 | 「基础资料 → 车闸对接」：状态/重连/使能回执/下发/删除/全量对齐/原始报文/上下行日志 |

## 8. 现场落地清单

### 8.1 线上状态 vs 调试状态（**两种网络形态不一样**）

| | 调试状态 | 线上状态（正常情况） |
|---|---|---|
| 相机网络 | 网线直连电脑，电脑与相机同一网段（如 `192.168.10.x` / 电脑共享出的 `192.168.137.x`） | 相机接现场交换机/路由器，**与办公 WiFi 是隔离的**：本机实测相机有线网段 `192.168.1.4`，办公 WiFi 是 `192.168.0.x` |
| 平台能否主动访问相机 | 能（`http://<相机IP>/pc/`、`Signalway.fcgi`） | **不能**（也不需要）：平台的访问由相机走 MQTT 主动连出来 |
| 判断是否正常 | 能打开相机 Web 后台 | **只看 broker 有没有心跳 / 平台有没有收到回执**；扫不到相机 Web 是正常现象 |

> 所以联调时那套「扫网段找 IP / 改 INI / 拉白名单核对」只能在调试状态下用；
> 线上只能用 MQTT 回执 + 平台记录判断（相机 `Conn` 上行里带 `devIP~...`，可用来确认它拿了哪个地址）。

### 8.2 落地步骤

1. 相机接**能上网的网线**（现场无电脑亦可），网络设为 **DHCP**（本机已验证）；
2. 相机「高级设置 → 平台配置 → 标准协议（MQTT）」：地址 `58.87.99.249`、端口 `1883`、账号 `fangke`、
   客户端 ID `{DevSerialNum}`、发布 `upload/{DevSerialNum}`、订阅 `download/{DevSerialNum}`、心跳 5s；
3. 「脱机白名单」里白名单控制模式选**自动开闸**（否则识别到也不抬杆）；
4. 相机第一次 `Conn` 上行后会自动登记到「设备管理 → 车闸相机 → 待分配」，在那把它分配到真实门点 + 方向（出口选「出口」）；
5. **不要用 4G**：实测 4G 出口会被运营商侧设备重置 MQTT（TCP RST，TTL 与相机包不一致）；有线网络正常。

## 9. 运维排查

### 9.1 相机本地 HTTP 接口（同网段时最好用）

```powershell
# 通用调用（Type/Class 必须是 CUSTOM/GETTER 或 CUSTOM/SETTER）
$body = 'XML[Value]=<?xml version="1.0" encoding="GB2312" standalone="yes"?><HvCmd ver="3.0"><CmdName Type="CUSTOM" Class="GETTER" Value="">命令</CmdName></HvCmd>'
Invoke-WebRequest -Uri 'http://<ip>/Signalway.fcgi?命令' -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/x-www-form-urlencoded' -UseBasicParsing
```

| 命令 | 用途 | Value |
|---|---|---|
| `GetIP` | 当前 IP/掩码/网关/MAC | 空 |
| `GetNameList` | 读白名单（含车牌、到期时间、车辆类型） | `0,100`（起始,条数） |
| `GetMqttRunStatus` | MQTT 状态（本机偶发 `Failed to Process`，以 broker 日志为准） | 空 |
| `DownloadINIFileEx` | 导出配置，返回 `RetMsg="ini/<ip>_<节名>.ini"`，再 `GET /<路径>` 下载 | 节名（传不存在的节名得到全量 76KB） |
| `ResetDevice`（MQTT）/ 断电 | 重启 | — |

> `GetWhiteListInfo` 在本机固件上会 500/502，改用 `GetNameList` + `Value="0,100"`。

### 9.2 改相机配置（不依赖 Web 后台）

1. `DownloadINIFileEx` 拿全量 INI；
2. 改文本（保持 UTF-8 无 BOM、行尾 CRLF）；
3. multipart `POST http://<ip>/upload`，字段名 `setFile`；
4. `UploadINIFileEx`（Type=STRING Class=SETTER，Value=上传的文件名）→ 返回 OK 后配置生效（`AutoGetIP`/`ResetDevice` 部分项需重启）。

### 9.3 常见现象

| 现象 | 原因 | 处理 |
|---|---|---|
| `errCode=1 "Unknow CMD."` | 固件不支持该命令（如 V2） | 换 V1 模板 |
| 下发成功但相机不抬杆 | 白名单控制模式不是「自动开闸」 | 相机「脱机白名单」改控制模式 |
| 白名单里有车牌但 `overdue_time=无` | 用了 type 4（贵宾车） | 改 `plate-type=1` |
| 平台下发 504 / 卡住 | 在 MQTT 回调线程里 publish 导致 Paho 接收线程被堵（旧版本），或连接半死 | 升级到含「回调线程异步 + 发布超时 + 看门狗」的版本；临时可点「重连」 |
| 相机连上就断 | 走 4G | 改有线；4G 出口会被中间设备重置 |
| 设备管理里没有车闸相机 | 相机还没 `Conn` 过一次（旧版本不会登记） | 相机联网后会自动登记到「待分配」；也可重启相机触发 `Conn` |

## 10. 业务联动（员工 / 访客 / 手动开闸 / 照片）

### 10.1 车牌自动跟随（员工与访客）

定时对账 `VehicleMqttPlateSyncJob`（默认 20 秒一轮，`fangke.vehicle.mqtt.sync-ms`）：

- **期望清单** = 在职员工车牌（`employee.status=1`）+ **当天**已授权访客车牌（`appointment.status in (35,40)` 且 `start_time` 在当天）；
- 与 `vehicle_mqtt_plate_state`（按设备+车牌记指纹）做差分：
  - 新车牌 / 内容变了（有效期等） → `AddCarInfo`；
  - 清单里没有（员工停用/删车牌、访客过期/取消） → `DeleteCarInfo`；
- 相机 `Conn` 上线时额外对齐一次，断网期间平台改过的也能追上；
- 员工没填有效期 → 按 **10 年**下发（不是"明天到期"）；访客按预约的 `end_time`。

### 10.2 手动开闸

`POST /api/admin/vehicle/mqtt/open`，`{deviceSn, plateNo?}`（页面：设备管理 → 车闸相机 → 开闸）：

- 下发协议 `Open`（`data` 为空串），相机回执 `errCode=0` 即已抬杆；
- **相机离线时直接报错**（按心跳 120 秒判断），避免"点了开闸但其实没发出去"；
- 带车牌时会按车牌判定一次并把匹配到的人写进 `vehicle_event`，因此**会出现在该人当天的通行记录里**；
- 不带车牌只记一条「人工开闸」（不进按人汇总）；
- 操作人写进审计日志（`VEHICLE/MQTT_OPEN_GATE`）。

> 手工「下发/更新车牌」是**临时**的：平台以员工档案 + 当天已授权访客为准，
> 下一轮对账（≤20 秒）会把这个车牌清掉（`DeleteCarInfo`）。
> 要长期生效，请把车牌填到员工档案或访客预约里。

### 10.3 通行记录与照片

- 相机 `Result` 上行 → `vehicle_event`：车牌(`carNO`)、置信度(`confid`)、方向（按设备档案的进/出）、判定结果；
- 抓拍图：相机给的是 OSS 外链（`picN_full` / `picMin_full`），存 `vehicle_event.photo_url`；
  通行记录明细里直接显示缩略图（`photo_key` 那套是设备直传 base64 的旧通道，两者二选一）；
- **离线补传**：相机 `Conn` 后平台自动下发 `Config{enableReply:1, enableOffline:1}`
  （协议 §4.4.22）。不开的话，网络抖动期间识别到的车会**丢记录**，恢复后也不补；开了就会缓存补传。
- **平台侧不丢**：网关用**持久会话（cleanSession=false）+ QoS1 订阅**，
  平台重启/掉线期间 broker 会把相机上行的识别结果排队，恢复后补投；
  补投消息可能早于业务监听器注册，网关做了启动缓冲，不会在启动瞬间被丢掉。
  已实测：停服 → 发一条 `Result` → 起服 → 事件正常入库（含照片 URL）。
- 通行记录汇总本来就合并 `vehicle_event`（按 `person_no` 归到人、按天聚合），
  只要判定得出来人（员工/有效访客）就会和行人记录合并成一行，`通行方式` 显示「车辆」。

### 10.4 实机验证（2026-11-26 00:37–00:44，线上状态）

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 员工信息录入车牌自动下发 | ✅ 建员工（苏A12345）→ 20 秒内 `AddCarInfo` → 回执 `errCode=0` |
| 2 | 访客预约车牌自动下发 | ✅ 当天已授权预约（苏B88888）→ `AddCarInfo`（到期=当天）→ 回执 0 |
| 3 | 员工停用同步删除 | ✅ 停用员工 → 下一轮 `DeleteCarInfo` → 回执 0 → 状态表清空 |
| 4 | 访客到期同步删除 | ✅ 预约改到昨天（等同过期）→ `DeleteCarInfo` → 回执 0 |
| 5 | 手动开闸 | ✅ `POST /mqtt/open` → `Open` → 回执 0；事件里记「人工远程开闸（admin）」 |
| 6 | 设备管理可见车闸相机 | ✅ 相机 `Conn` 自动登记并解析内网 IP（`devIP~192.168.1.4`），列表显示「在线 / 192.168.1.4」，已分配到「公司大门 / 出口」 |
| 7 | 开闸记录合并到通行记录 | ✅ 模拟 `Result` → 通行汇总按人出现「车辆 1 次」，明细含车牌/点位 |
| 8 | 通行照片 | ✅ `Result.picN_full` → `photo_url` → 通行明细接口返回该图 |
| 9 | 全程走线上状态 | ✅ 全部经云服务器 MQTT 下行/上行完成，未使用局域网直连 |

## 11. 三小时巡检：发现与修复（2026-11-26 01:20–04:20）

> 巡检方式：线上环境（生产服务器 + 真机相机）逐字段、逐接口、逐场景验证，发现即修、修完回归。

### 11.1 字段级加固（V37 / V38）

| 问题 | 影响 | 修复 |
|---|---|---|
| `photo_url` 只有 512，OSS 带签名 URL 可能更长 | STRICT 模式下 INSERT 失败 → **整条通行记录丢失** | V37 放宽到 1024；落库前按列宽截断（`plate_no` 16 / `matched_name` 64 / `fail_reason` 255…） |
| `raw_json` 是 JSON 列 | 相机发一条不合法 JSON → INSERT 失败 → 记录丢失 | V37 改 LONGTEXT，原文照存不校验 |
| `confidence` 注释 0-100，相机给 0-1000 | 数据口径不一致 | 入库前折算 `confid/10` 并收敛到 0-100 |
| 通行记录按「结果+人+时间」查询 | 无合适索引 | V37 补 `idx_vehicle_result(result_status, person_no, event_time)` |
| 员工/访客车牌保存原样（小写/空格） | 判定按字符串比对 → **相机会开闸但平台判「未登记车辆」**，记录挂不到人 | 新增 `PlateNo` 统一口径（大写、去空格，写入时校验长度）；V38 批量修正历史数据 |

### 11.2 可靠性

| 问题 | 影响 | 修复 |
|---|---|---|
| 下行默认 QoS0 | 实测 50 条车牌只收到 44 条应用回执，**静默丢消息可能导致现场开不了门** | 默认 QoS 改 **1**（broker 重投直到相机 PUBACK）；实测 50 条 **broker PUBACK 50/50**、0 失败 |
| 事件时间用「入库时刻」 | 相机离线补传的记录会串到补传那天（跨零点/跨天全错） | 优先用相机 `triggT` / `imgT`；超前 5 分钟或早于 7 天的回落服务器时间 |
| 相机 QoS1 重传同一条结果 | 同一次通行重复入库 | 有 `guid` 就用它做事件唯一键（`MQTT-<GUID>`），实测同消息发两次只落 1 条 |
| 相机恢复出厂/白名单被清后重连 | 平台以为「已下发」，实际相机里是空的 | `Conn` 时清掉该设备的对账状态并**强制全量重推**（实测重启相机后 `Config` + `AddCarInfo` 都重发） |
| 删除设备档案 | 对账状态残留，重新登记同一台设备时「以为已下发」 | 删除 VEHICLE 设备时一并清 `vehicle_mqtt_plate_state` |
| 手动「全量对齐」 | 对齐后状态没更新，下一轮定时任务又推一遍 | 对齐时同步写对账状态、并清掉清单外的车牌状态 |
| `vehicle_mqtt_log` 无保留期 | 长期堆积 | 纳入每日保留期清理（默认 30 天，`fangke.job.vehicle-mqtt-log-retention-days`） |
| 相机拒绝下行（`errCode=1`）只写日志表 | 巡检时容易漏 | 同时 `WARN` 到服务器日志（带 `errInfo`） |
| 相机回 `errCode=1`（实测出现过 `White name list process busy.`） | 那条名单**实际没生效**，但平台以为已下发 → 现场可能开不了门 | 收到失败回执就清空该设备的对账状态，下一轮定时任务整体重推（20 秒内自愈） |

### 11.3 业务一致性

| 问题 | 影响 | 修复 |
|---|---|---|
| 报表只统计 `access_event` | 总览/趋势/按设备统计**完全不含车闸** | 总览、每日趋势、按设备、按识别方式全部并入 `vehicle_event`；识别方式显示「车牌识别」；按设备显示设备名称 |
| 「车牌名单 → 下发到车闸相机」按钮走旧适配器 | 实际返回 `PENDING_VENDOR`，**按钮等于没用** | 车闸相机改走 MQTT 对账下发，返回「MQTT 已下发（新增/更新/删除/未变）」 |
| 车牌快照逐单查门点与访客姓名 | 预约多时每 20 秒 N+1 查询 | 改批量 `IN` 查询 |
| nginx 反代后 `remoteAddr=127.0.0.1` | 被当成内网 IP 写进设备档案 → 「本地管理」跳到本机 | `isPrivateAddress` 排除 127./::1；设备 IP 只认真实内网地址 |
| 对账并发（定时任务 + `Conn` 触发） | 可能重复推送 | `syncDevicePlates` 加同步锁 |
| 访客审批后**被拉黑** | 车牌仍在相机白名单里，车照样能开进来 | 车牌快照排除 `visitor.is_blacklist=1` 的访客；实测拉黑 → `DeleteCarInfo`，解除拉黑 → `AddCarInfo` |
| 相机**离线**时点「开闸」 | 指令发出去就丢（相机 cleanSession=1），接口却回成功，让人以为闸开了 | 开闸前按心跳（120 秒）判断在线，离线直接 400 并带上最近心跳时间 |

### 11.4 压测与实测记录

| 场景 | 结果 |
|---|---|
| 50 个员工车牌一次下发 | 50 条 `AddCarInfo` 在 **0.6 秒**内发完，broker PUBACK 50/50，应用回执 44 条（相机应用层回执非严格一一对应），`errCode` 失败 0 |
| 50 个车牌全部删除 | 50 条 `DeleteCarInfo`，状态表清空，失败 0 |
| 乱码/超长车牌（25 字符） | 截断到 16 入库，不丢记录 |
| 非 JSON 的 `data` | 只留痕不报错 |
| 小写+空格车牌 `" 苏l 12345 "` | 存成 `苏L12345`，相机下发一致，识别回来判定「员工车辆」 |
| 旧 OpenAPI 通道（`/openapi/v1/vehicleEvent`） | 回归通过，设备 IP 不再被污染 |
| 日志保留期清理 | 造一条 40 天前的 MQTT 日志 → 手动跑 `DATA_RETENTION` → 已清理（affected=1） |
| broker 重启 | 重启 mosquitto 后相机 1 秒内重连、网关 1 秒内重连（持久会话），随即触发 `Conn` 配置 + 车牌重推 |
| 超大原始报文（200KB） | 295ms 内发出，日志按 20KB 截断，相机回 `Data Parse Json Fail.` 并 WARN 落日志 |
| 立即对齐接口 SN 打错 | 返回 400「设备不存在」，不再往不存在的主题发报文 |
| 相机回执失败自愈 | 发一条 `data` 非 JSON 的 `AddCarInfo` → 相机回 `errCode=1 Data Parse Json Fail.` → **对账状态立即清空** → 下一轮（≤20s）自动重推 → 状态恢复 |
| 访客拉黑/解除 | 有效预约 `苏T11111` 已下发 → 置 `is_blacklist=1` → 下一轮 `DeleteCarInfo`；再改回 0 → `AddCarInfo` 恢复 |
| 停机期间改数据 | 停服 → 删员工 → 起服：20 秒内 `DeleteCarInfo` 删掉相机里的车牌（状态收敛） |
| 离线相机开闸 | 造一台 10 分钟没心跳的设备 → 开闸返回 **400**「车闸相机当前离线（最近心跳 …）」；在线相机同接口 200 |
| 手工下发车牌 | `POST /mqtt/plate 苏U11111` → `AddCarInfo` + 立即写对账状态 → 17 秒后定时对账按平台数据把它删掉（`DeleteCarInfo`，符合预期：平台是唯一事实源） |

### 11.5 终验（2026-11-26 02:19–02:25，最终构建）

| # | 项 | 结果 |
|---|---|---|
| 1 | 员工车牌下发（无有效期） | ✅ `苏Q11111` → `AddCarInfo`（`time` = 10 年后）→ 回执 0 |
| 2 | 访客车牌下发（当天授权） | ✅ `苏Q22222` → `AddCarInfo`（`time` = 当天）→ 回执 0 |
| 3 | 识别结果入库 | ✅ 模拟 `Result`（`guid` + 照片）→ 放行 / 置信度 95 / 员工 / 照片 URL |
| 4 | 通行记录合并 | ✅ 通行汇总按人显示「车辆」，明细含车牌、点位、识别方式 |
| 5 | 报表统计 | ✅ 总览 `events=1 pass=1`，按设备显示相机名称、按识别方式显示「车牌识别」 |
| 6 | 手动开闸 | ✅ `Open` → 回执 0；明细里识别方式显示「人工开门」（之前会显示「车牌识别」） |
| 7 | 员工停用 / 访客到期删除 | ✅ 两条 `DeleteCarInfo` → 回执 0 → 对账状态清空 |
| 8 | 相机状态 | ✅ 在线、内网 IP `192.168.1.4`、门点「公司大门 / 出口」 |
| 9 | 失败与回执 | ✅ 5 条下行 / 6 条上行，`errCode` 失败 0 |

### 11.6 已知限制

1. **方向**只能靠设备档案配置：协议 `Result` 里没有进/出字段；双向相机（direction=2）只能显示「双向(未知)」，按进/出统计时会漏计。
2. **应用层回执**（相机回 `errCode`）不是严格逐条：50 条可能只回 44 条；判断是否送达应以 broker PUBACK（QoS1）为准。
3. 相机若开启**车牌加密**，`carNO` 是密文，平台无法解析，需关闭加密或约定密钥。

## 12. 第二轮巡检：功能实测与边界（2026-11-26 02:40–04:20，线上状态）

第一轮解决「丢数据/丢记录」，第二轮按**业务功能逐项实测**（全部在线上状态：相机走现场网连公网 broker `58.87.99.249:1883`，与办公网隔离）。

### 12.1 修复项

| 编号 | 问题 | 现象 | 处理 |
|---|---|---|---|
| C-95 | 车闸能力位与 MQTT 通道不一致 | 管理端能力矩阵里「名单下发/远程开闸」显示不支持；补能力位后又会被旧适配器分支吃掉返回 `PENDING_VENDOR` | `DeviceCapabilityService` 在 `VEHICLE` + MQTT 启用时补 `PLATE_UPSERT/PLATE_REMOVE/DOOR_OPEN`；`VehiclePlateService` 的 MQTT 分支前移到能力检查之前 |
| C-96 | 车牌字符未过滤 | 车牌含 `"` `\` `{` `}` 会把 `AddCarInfo` 的 JSON 拼坏，相机整条丢弃且平台无感 | `PlateNo.normalize` 改字符白名单（汉字/字母/数字 + `·` `•` `-`）；下发与对账状态表读写前再规范化 |
| C-97 | 设备页默认筛选 | 默认停在「人行终端」，只装车闸相机的现场打开是空列表 | 默认「全部」 |
| C-98 | 工作台「设备在线」口径 | 断电设备 `online_state` 残留 1，显示「全部在线」，实际人行终端已离线 6 小时 | 按心跳新鲜度统计（车闸 180 秒宽限），与设备列表口径一致 |
| C-99 | 置信度字段 | 只读协议字段 `confid`，个别固件用 `confidence` 时静默存 NULL | `confid` 优先（0-1000 折算），`confidence` 兜底（≤100 视为百分比） |
| C-100 | 报表识别方式 | 车闸事件一律算「车牌识别」，人工开闸也显示车牌识别，与通行明细不一致 | 按 `fail_reason LIKE '人工远程开闸%'` 拆成 9（人工开门）/ 20（车牌识别） |

### 12.2 实测记录（线上）

| 场景 | 步骤 | 结果 |
|---|---|---|
| 员工车牌下发 | 建员工 `JS0002 / 京a 12345` | 入库规范化 `京A12345`；约 15 秒后 `AddCarInfo`，相机回 `errCode=0`，对账状态写入 |
| 员工停用删除 | 对同一员工执行停用 | ≤20 秒 `DeleteCarInfo` → 回执 0 → 对账状态清空 |
| 访客车牌下发 | 预约单置为已批准 + 挂门点 + 车牌 `苏e 66666` | 规范化 `苏E66666`；`AddCarInfo` 的 `time` = 当天 |
| 访客到期删除 | 同一预约改到昨天 | ≤20 秒 `DeleteCarInfo` → 回执 0 → 状态清空 |
| 识别入库 | 向 `upload/<SN>` 注入 `Result`（`confid=950`、`picN_full`、`triggT` 90 秒前） | `confidence=95`、`matched_type=1`、员工姓名/编号正确、`event_time` 取 `triggT`、`photo_url` 落库 |
| 通行记录合并 | 查 `/api/admin/traffic/summary` 与 `/{personNo}/detail` | 汇总按人显示「车辆」、出 1 次；明细含车牌、设备名称、门点、「车牌识别」、抓拍图 |
| 报表并车 | 查 `/api/admin/report/access` | 按设备出现「出口道闸相机 1」、按方式出现「人工开门 1」 |
| 手动开闸 | `POST /mqtt/open`（不带车牌） | `Open` → 回执 0；记录一条「人工远程开闸（admin）」 |
| broker 重启韧性 | `systemctl restart mosquitto` | 网关 **2 秒**内重连；相机立即重连（公网 117.89.73.58）并重新 `Conn`；心跳 8 秒内恢复；全程 0 WARN/ERROR |
| 长稳采样 | 02:46–03:52 与 03:24–04:11 两轮每分钟采样（RSS / 心跳年龄 / 对账状态 / 告警数） | 第一轮 RSS 294→315MB（期间多次重启部署）；第二轮 03:23 重启后 298→306MB，47 分钟仅 +8MB 且已收敛（≈0.17MB/min），心跳年龄始终 15–17 秒，对账状态与平台数据一致，`journalctl` 近 1 小时 0 条 WARN/ERROR |

### 12.3 现场上线检查清单

1. 相机 `平台设置 → 第三方平台` 选 MQTT，服务器填 **公网 IP `58.87.99.249`**、端口 **1883**、用户名/密码 `fangke` / `mAX1nJyPpviu0cTeo49Sxa6K`。
2. 现场路由器要放行出站 TCP 1883（有些酒店/园区网络只放 80/443）。
3. 相机上报的设备 SN 要能在「设备管理 → 车闸相机」里对上，门点方向（进/出）在档案里配置。
4. 平台侧只需维护员工/访客车牌，**不需要**在相机上手工加名单：平台每 20 秒差分对账，相机 `Conn` 时会全量重推。
5. 断网/断电恢复后无需人工干预：相机重连会自动 `Conn` → 平台重推名单；离线期间的识别结果由相机补传（平台用 `guid` 去重）。
