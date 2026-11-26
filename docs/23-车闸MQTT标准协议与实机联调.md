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

1. 相机 `平台设置 → 第三方平台` 选 MQTT，服务器填 **公网 IP `58.87.99.249`**、端口 **1833**、用户名/密码 `fangke` / `mAX1nJyPpviu0cTeo49Sxa6K`。
   > broker 在服务器上同时监听 1883 与 1833，但**云防火墙只放通了 1833**（实测本机连 1883 不通、1833 通），所以相机必须填 **1833**。
2. 现场路由器要放行出站 **TCP 1833**（有些酒店/园区网络只放 80/443）。
3. 相机上报的设备 SN 要能在「设备管理 → 车闸相机」里对上，门点方向（进/出）在档案里配置。
4. 平台侧只需维护员工/访客车牌，**不需要**在相机上手工加名单：平台每 20 秒差分对账，相机 `Conn` 时会全量重推。
5. 断网/断电恢复后无需人工干预：相机重连会自动 `Conn` → 平台重推名单；离线期间的识别结果由相机补传（平台用 `guid` 去重）。

## 13. 第二台相机（入口）接入记录与「放行规则」修正（2026-11-26 08:15–08:30）

### 13.1 关键发现：出厂的白名单是「脱机后自动开闸」

入口相机调试时发现「调试向导 → 白名单配置 → 控制模式」里，白名单出厂是 **脱机后自动开闸**，含义是：

- **相机在线**：识别到车牌后只把 `Result` 报给平台，**自己不开闸**，等平台下发 `Open`；
- **相机离线**：才按本地白名单自动开闸。

而我们的定位是「平台维护名单、相机本地放行」（现场没有电脑、网络抖动也不能挡住员工），所以在线态下按原出厂设置**闸不会开**。
协议 §4.4.6 的 `DlPassRule` 正是这条规则的开关：

| 字段 | 含义 | 取值 |
|---|---|---|
| `wcarTiming` | 白名单启用机制 | 0 离线 / 1 在线 / 2 不启用 / 3 全启用 |
| `tcarTiming` | 临时车（不在名单的车） | 同上 |
| `bcarTiming` | 黑名单 | 同上 |
| `wcarAct` / `tcarAct` / `bcarAct` | 屏显 + 语音 + **闸机动作**（`action` 1 开闸 / 0 不开闸） | JSON 字符串 |

**处理**：平台在相机 `Conn` 时除 `Config`（回执 + 离线补传）外，再下发一条 `DlPassRule`：

- `wcarTiming=3`（全启用）、`wcarAct.action=1` → 名单车到闸口由相机自己开闸，**断网也能进**；
- `tcarTiming=2` / `bcarTiming=2` + `action=0` → 临时车、黑名单不自动放行；
- 三个 timing 与开关都可在配置里改（`fangke.vehicle.mqtt.wcar-timing` 等，`push-pass-rule` 可整体关掉）。

> 已实测：模拟 `Conn` 上行后，平台立刻向 `download/COPCM-E10-LS26050979` 发出 `Config` + `DlPassRule`，报文均为合法 JSON。

### 13.2 入口相机配置记录

| 项 | 值 |
|---|---|
| SN | `COPCM-E10-LS26050979` |
| 本地管理地址 | `http://169.254.35.151/pc/`（与电脑直连时的链路本地地址），密码 `123456` |
| MAC | `0c:f4:05:fe:37:4b` |
| 型号 / 固件 | PCM500FSPUTV14（与出口相机同型号同固件） |
| 平台选择 | 调试向导 → 平台配置 → **标准协议（MQTT）** 使能 |
| 平台地址 / 端口 | `58.87.99.249` / **1833**（公网只放通了 1833，1883 被云防火墙挡住） |
| 用户名 / 密码 | `fangke` / `mAX1nJyPpviu0cTeo49Sxa6K` |
| 客户端 ID | `{DevSerialNum}` |
| 发布 / 订阅主题 | `upload/{DevSerialNum}` / `download/{DevSerialNum}` |
| Rtd 心跳间隔 | 30 秒 |
| 消息回执 / 离线结果重传 / QOS1 上传 | 全部打开 |
| 网络 | **自动获得 IP（DHCP）**，重启后已生效；直连无 DHCP 时回落到 `169.254.35.151` |
| 白名单控制模式 | 自动开闸（同时平台会下发 `DlPassRule` 覆盖，双保险） |
| 平台侧设备档案 | 设备管理 → 车闸相机：`入口道闸相机`｜门点 `公司大门`｜方向 `入口`｜通道 `VEHICLE` |

> 换到现场网络后：网线插上 → DHCP 拿到地址 → 相机自动连 `58.87.99.249:1833` → 上行 `Conn` → 平台自动下发配置/放行规则并全量重推车牌。

### 13.3 待现场确认：入口相机连上 broker 后 1 秒内自己断开（2026-11-26 08:35）

调试时的现象（全部有据可查，见 broker 日志与相机「系统维护 → 日志查询」）：

1. 相机填 `58.87.99.249:1883` 时：TCP 都连不上（云防火墙只放通 1833），相机日志报 `No route to host`，状态一直「未连接」。
2. 改成 **1833** 后：TCP 能连上，broker 侧记录到
   `New client connected from 223.104.83.253 as COPCM-E10-LS26050979 (p2, c1, k30, u'fangke')` → `Sending CONNACK (0,0)` → `Client COPCM-E10-LS26050979 closed its connection.`
   即**认证通过**，但相机在收到 CONNACK 后约 0.3–1 秒主动断开，每 6 秒重试一次；broker 侧**没有收到任何上行报文**（连 `Conn` 都没有），相机日志只写 `连接断开, 断开原因: The connection was lost.`。
3. 同一时间这台相机还带着 **4G 模块**（源 IP `223.104.83.253` 是中国移动），而且它连**厂商云**（`entry-xltmq.sn6.co:80`）是通的——日志里有 `连接成功` 后立刻收到 `GetImage_Down` / `使能上传IN1状态` 等云端指令。说明 4G 链路本身没问题，**只是连我们的 broker 时被对端/中间设备掐断**。
4. 用同一台电脑直连 `58.87.99.249:1833` 做裸 MQTT 会话测试（CONNECT → CONNACK → 20 秒后 PINGREQ/PINGRESP）：**连接稳定不掉**，说明 broker 与端口在宽带上没问题。

**判断**：最可能是「4G 网络对非标端口（1833）的 MQTT 长连接做了限制」，而现场是**有线宽带**（出口相机就是从 `117.89.73.58` 稳定连了很久），所以先用现场网线验证。

**现场验证步骤**（3 分钟）：

1. 相机插到现场路由器（DHCP 已开），「网络设置」确认拿到地址、能上网；
2. 看相机「平台配置」状态是否 `已连接`；看平台「车闸对接」页是否出现 `download/COPCM-E10-LS26050979` 的下行、设备心跳是否刷新；
3. 若仍反复断开：把「平台配置 → 标准协议（MQTT）」的 **客户端 ID 改成其它值**（先排除同 ID 冲突），再不行就联系厂商要「标准协议 MQTT 连接即断开」的排查方法，或把固件与出口相机对齐（出口：`21.2.0.3045 / APP 21.1.22.15541_20260408`；入口这台：`21.2.0.2551 / APP 21.1.22.15848_20261030`，版本更新）。

> 另外注意：这台相机**已激活（蜂巢/路通云停）**，且设置里多次出现重启后平台参数回到厂商默认（`entry-xltmq.sn6.co:80`）的情况；怀疑是「平台不可达时的回退」。等现场连通后再重启一次，确认参数是否还会被改回。

## 14. 相机本地配置操作手册（实录：2026-11-26 入口相机）

> 适用于 PCM500FSPUTV14 / YC-P208 这类「Web 调试助手」界面的相机（`http://<相机IP>/pc/`）。

### 14.1 操作步骤

| # | 步骤 | 操作要点 |
|---|---|---|
| 1 | 连上相机 | 网线直连电脑时相机是 `169.254.x.x`（无 DHCP 时的链路本地地址）；插到路由器后由 DHCP 分配（本机实测 `192.168.1.5`）。浏览器打开 `http://<IP>/pc/`，账号 `admin`，密码见设备贴纸/现场设定（本例 `admin`） |
| 2 | 网络 | 高级设置 → 网络设置 → 打开「自动获得 IP」（DHCP），应用后**重启才生效**；网关/DNS 用 DHCP 下发的即可 |
| 3 | 平台配置 | 高级设置 → 平台配置 → **标准协议（MQTT）**：使能打开；平台地址 `58.87.99.249`；端口 **1833**；用户名 `fangke`；密码 `mAX1nJyPpviu0cTeo49Sxa6K`；客户端 ID `{DevSerialNum}`；发布主题 `upload/{DevSerialNum}`；订阅主题 `download/{DevSerialNum}`；Rtd 心跳 30 秒；**消息回执 / 离线结果重传 / 使用 QOS1 上传结果** 三个开关都打开 |
| 4 | 放行规则 | 调试向导 → 白名单配置 → 白名单控制模式选 **自动开闸**（出厂是「脱机后自动开闸」，见 §13.1）。平台连上后还会自动下发 `DlPassRule` 覆盖一次，双保险 |
| 5 | 保存 | 右上角「保存所有」→ 提示「参数保存成功」；若弹出「某些参数需要重启设备后才生效」，**先点取消，等 30 秒**再走 系统维护 → 重启设备（避免配置还没落盘就重启） |
| 6 | 平台侧 | 相机上行 `Conn` 后会自动登记到「设备管理 → 车闸相机」，把**门点**和**出入口方向**改对（入口/出口），平台就开始按门点对账下发车牌 |
| 7 | 验证 | 相机首页「标准协议(MQTT)」显示 `已连接`；平台「车闸对接」页能看到 `upload/…`（Conn/Rtd）与 `download/…`（Config/DlPassRule）报文；设备列表心跳开始刷新 |

### 14.2 一定要知道的坑（按踩坑顺序）

1. **端口只能填 1833**：服务器上 mosquitto 同时监听 1883/1833，但云防火墙当时只放通了 1833；填 1883 相机会报 `No route to host`。后来 1883 也放开了，但**两台相机统一用 1833**，避免现场再改。
2. **界面里的地址/用户名/密码显示成 `Enc[...]` 是正常的**（加密回显），不是填错；直接点进去全选覆盖输入即可，保存后密文会变。
3. **不能用脚本直接改 `input.value`**：Vue 的双向绑定不认，界面看着变了但保存下去还是旧值（我第一次就这样白折腾一轮）。必须像真人一样点进去输入。
4. **重启后平台参数可能回到厂商默认**（`entry-xltmq.sn6.co:80` / `xltadmin`）：这台机器是「已激活（蜂巢/路通云停）」状态，重启后疑似从厂商云拉回默认预设。**所以配置完先不要重启**；真的重启了，就重新填一遍 4 个字段再保存。
5. **双网络（4G + 有线）抢主用，且同一个 clientId 会被 MQTT 踢下线**：broker 日志表现为 `New client connected … CONNACK … Client closed its connection` 循环，相机日志刷 `连接断开, 断开原因: The connection was lost.`。
   - 判断方法：`sudo grep 'New client connected' /var/log/mosquitto/mosquitto.log | tail`，若同一 SN 来自**两个不同公网 IP**（本例 `223.104.83.253`=移动 4G、`117.89.73.58`=现场宽带），就是两条链路在抢。
   - **真正的开关在「系统维护 → 专家模式 → NetComManager → Priority」**：
     - `第1优先级网关 No1GateWay`：`0 无 / 1 RJ45 / 3 移动网络`，**出厂是 3（移动网络）**——这就是 4G 明明没配平台却总是走 4G 的原因；
     - `第2优先级网关 No2GateWay`：改为 `无`，避免两条链路来回切。
     - 改完点右上「保存所有」，提示参数保存成功后**重启**才彻底生效。
   - 实测：第1=移动网络 时，MQTT 一直从 4G 出口建连并 0.3 秒被杀（4G 侧链路/端口策略问题）；改成 `第1=RJ45、第2=无` 后，相机改从现场宽带走，`Conn`、`Config`、`DlPassRule` 都能正常收到并回执。
   - 临时应急（服务器侧）：`iptables -I INPUT -s <4G出口IP> -p tcp --dport 1883 -j REJECT`，把 4G 那条掐掉，有线那条才能稳定。
6. **出厂白名单＝脱机后自动开闸**：在线时相机只报结果、不开闸，必须改成「自动开闸」或由平台下发 `DlPassRule`（§13.1）。这是最容易被漏掉、现场表现为「识别到了但闸不开」的坑。
7. **相机没有 4G 开关页面**：4G 模块只要插着 SIM 就会自己联网，界面上找不到关闭项；要么拔 SIM，要么靠上面的 iptables 临时挡住。
8. **DHCP 生效要重启**；直连电脑（无 DHCP 服务器）时会回落到 `169.254.x.x`，别以为是配置没生效。

### 14.3 两台相机的实际配置留档（2026-11-26）

| 项 | 出口 `COPCM-E10-LS26050994` | 入口 `COPCM-E10-LS26050979` |
|---|---|---|
| 平台地址/端口 | `58.87.99.249:1833` | `58.87.99.249:1833` |
| 账号 | `fangke` / `mAX1nJyPpviu0cTeo49Sxa6K` | 同左 |
| 主题 / 客户端 ID | `upload|download/{DevSerialNum}` / `{DevSerialNum}` | 同左 |
| 回执 / 离线补传 / QOS1 | 全开 | 全开 |
| Rtd 心跳 | 5 秒 | 30 秒 |
| **网关优先级** | **第1=RJ45、第2=无**（出厂 第1=移动网络） | **第1=RJ45、第2=无** |
| 白名单控制模式 | 自动开闸 | 自动开闸 |
| 平台侧档案 | `出口道闸相机`｜公司大门｜方向=出口 | `入口道闸相机`｜公司大门｜方向=入口 |

> 两台出厂的第1优先级网关都是「移动网络」，4G 会抢走 MQTT 连接并和有线同 clientId 互踢——这是「只有一个设备在线/一直连不稳」的根因之一。改成 RJ45 优先后两台都能稳定在线。

## 15. 强制同步（清空重推）：纠正相机侧被人工改过的名单

### 15.1 为什么需要

协议**没有「读名单」指令**（`PLATE_LIST` 不支持），平台只能靠「下发回执 `errCode=0` + 自己的对账状态表」来判断名单是否生效。
如果有人直接在相机界面/调试工具里导入或删除车牌，平台**看不到也纠正不了**——直到相机重新 `Conn` 才全量重推，但重推只做差量（新增/删除），相机里多出来的**未知车牌不会被删掉**。

### 15.2 做法：ClearData + 全量重推

协议 §4.4.23 提供了 `ClearData`：

```json
{"command":"ClearData","data":"{\"clearCarInfo\":1}", ...}
```

`clearCarInfo=1` 会清掉相机内的**固定车信息（名单）+ 放行规则**，回执为 `ClearDataReply{errCode}`。

平台按钮「**强制同步（清空重推）**」的执行序列（实测 2.6 秒）：

| 时间 | 方向 | 内容 | 结果 |
|---|---|---|---|
| 11:01:30.600 | DOWN | `ClearData{clearCarInfo:1}` | — |
| 11:01:30.690 | UP | `ClearDataReply{errCode:0}` | 相机确认清空 |
| 11:01:33.129 | DOWN | `AddCarInfo 京A12345` | — |
| 11:01:33.238 | UP | `errCode=0` | 重推成功 |
| 11:01:33.150 | DOWN | `DlPassRule{白名单全启用}` | 放行规则被 ClearData 清掉了，必须补 |
| 11:01:33.682 | UP | `OverUpdate{carinfo:0,passruler:1}` | 相机接受规则 |

> 清空后必须重新下发 `DlPassRule`，否则相机只剩名单、没有放行规则，**识别到也不开闸**。

### 15.3 在哪里点

- 设备管理 → 车闸相机行 →「车牌名单」弹窗 → **强制同步（清空重推）**
- 车闸对接 → 选好设备 → **强制同步**

接口：`POST /api/admin/vehicle/mqtt/force-resync?deviceSn=xxx`
- 设备不存在 / 离线（心跳 >120 秒）直接报错，不会假装成功；
- 返回 `{cleared:true, repushed:{desired,added,updated,removed,unchanged}}`；
- 全流程写审计日志（`MQTT_FORCE_RESYNC`）与 `vehicle_mqtt_log`，现场可回溯。

### 15.4 使用建议

| 场景 | 用什么 |
|---|---|
| 日常增删改车牌 | 什么都不用点，20 秒定时对账自动处理 |
| 刚改完想立刻生效 | 「立即对齐」/「下发到车闸相机」（差量，快） |
| **怀疑相机名单被人工改过** | **强制同步（清空重推）** |
| 相机恢复出厂/换了新相机 | 相机 `Conn` 时自动全量重推，无需人工 |

## 16. 抓拍图：不走云 OSS，相机 BASE64 回传 + 平台自存

### 16.1 问题

相机上报的 `picN_full` 是**它自己传阿里云 OSS 后生成的链接**，而现场相机的 OSS 仍是厂商的测试桶/密钥：

```
picN_full = http://weiboyun-oss.oss-cn-hangzhou.aliyuncs.com/picture/684511/COPCM-.../20261126112000.jpg
→ 实测 HTTP 404（图根本没传上去）
```

结果：通行记录里「照片」列是裂图，等于没有抓拍。

### 16.2 做法

协议 §4.4.1 的 `Image` 命令可以指定图片上传方式：

```json
{"command":"Image","data":"{\"type\":0}", ...}   // 0 = BASE64 走 MQTT；1 = 相机传阿里云 OSS
```

平台改造：

1. 相机 `Conn` 时自动下发 `Image{type:0}`（配置项 `fangke.vehicle.mqtt.image-base64`，默认开）；
2. 识别结果里的 `picNFile`（大图，兜底 `picMinFile`）取出 → 存 MinIO；
3. **`raw_json` 里把 BASE64 抠掉**再入库（一张图几百 KB，整段存进去会把库撑爆，实测从 300KB 降到 243 字节）；
4. 通行明细里 `photo_url` 为空时，用 `photo_key` 生成预签名链接回填。

MinIO 只监听 `127.0.0.1:9000`，浏览器够不到，所以 nginx 反代：

```nginx
location /media/ {
    proxy_pass http://127.0.0.1:9000/;
    proxy_set_header Host 127.0.0.1:9000;   # 必须改回内网 host，预签名才验得过
    proxy_http_version 1.1;
    proxy_set_header Connection "";
}
```

预签名 URL 里的内网 endpoint 由 `S3_PUBLIC_BASE=http://58.87.99.249/media` 重写。

### 16.3 实测

| 步骤 | 结果 |
|---|---|
| 下发 `Image{type:0}` | 两台相机均回 `errCode=0` |
| 模拟一条带 BASE64 的 `Result` | `vehicle_event.photo_key = vehicle-event/20261126/...jpg`，`raw_json` 243 字节 |
| 通行明细 `photoUrl` | `http://58.87.99.249/media/fangke/vehicle-event/...jpg?X-Amz-...` |
| 直接访问该链接 | **HTTP 200，`image/jpeg`，内容正确** |

> 预签名有效期 5 分钟（`fangke.storage.presign-ttl`），每次打开页面重新生成；桶仍是私有的，不暴露直链。

## 17. 上线前收尾：同行人按人记、回执可见、残留清理、日志轮转（2026-11-26 11:20–12:10）

现场三台设备（大门入口行人终端 `2608252243655`、入口相机 `COPCM-E10-LS26050979`、出口相机 `COPCM-E10-LS26050994`）全部在线，本轮把「上线前必须补齐的体验与事故预防」一次做完。

### 17.1 团队预约：一辆车按人各记一条通行记录

**问题**：`appointment.mate_count` 只是同行人数量，`VehicleAccessService.judge()` 只返回一个匹配人 —— 一辆车带 8 个人进门，平台只有 1 条记录，通行汇总的人次会少算。

**实现**：

| 位置 | 改动 |
|---|---|
| `VehicleAccessService.judgeGroup()` | 返回该车牌此刻可通行的**所有人**：员工 1 人；访客 = 主访客 + `appointment_mate` 里 `is_main=0` 的同行人 |
| `VehicleAccessService.recordGroup()` | 按人逐条落库；事件号 `抓拍事件号`、`抓拍事件号-1`、`...-2`，相机重复补传仍然幂等 |
| 抓拍图 | 只上传一次，其余记录引用同一个 `photo_key`；`raw_json` 只在第一条存一份（避免一条车事件写 N 份大报文） |
| 同行人编号 | `appointment_mate.person_no` 目前为空（M6 未分配）时用 `预约号-序号` 兜底，例如 `V202611260099-1` |
| 其它入口 | `VehicleOpenApiController`（第三方推送识别结果）同样改走 `judgeGroup/recordGroup`，返回里多给一个 `personCount` |

**实测**（种子数据：访客 `V202611260099` + 同行人甲/乙，车牌 `京B99999`，门点 1）：

1. `POST /admin/dispatch/V202611260099` → `success=1`，预约状态 30 → 35（GRANTED），`access_grant` 1 条、车牌推到两台相机
2. 用 `mosquitto_pub` 往 `upload/COPCM-E10-LS26050979` 发一条 `Result{carNO:京B99999}`（模拟相机识别）
3. `vehicle_event` 得到 3 条：

| event_uuid | person_no | matched_name |
|---|---|---|
| `MQTT94ab...87ee` | `V202611260099` | 团队测试主访客 |
| `MQTT94ab...87ee-1` | `V202611260099-1` | 同行人甲 |
| `MQTT94ab...87ee-2` | `V202611260099-2` | 同行人乙 |

4. 通行汇总按人分 3 行（各 1 进 0 出），`/{personNo}/detail` 也能按同行人编号查到明细

> 撤销授权后 25 秒内对账把车牌从两台相机删掉（`下行：删除车牌 京B99999` + 回执成功），`vehicle_mqtt_plate_state` 只剩 `京A12345`。

### 17.2 车牌名单加「下发时间 / 回执状态」

`GET /api/admin/vehicle/plate?deviceSn=<SN>` 现在额外返回：

- 每条车牌：`pushedAt`（对账状态表里的最近下发时间）、`mqttStatus`、`mqttStatusText`
- 整表：`lastReply`（这台相机最近一次下行回执）

| `mqttStatus` | 文案 | 判定 |
|---|---|---|
| `NOT_PUSHED` | 未下发 | 对账状态表里没有这条车牌 |
| `PUSHED` | 已下发（暂无回执） | 有下发记录、但这条设备还没有任何回执 |
| `WAITING` | 等待回执 | 最近回执时间**早于**这条车牌的下发时间 |
| `CONFIRMED` | 相机已确认 | 最近一次回执成功，且晚于下发时间 |
| `REJECTED` | 回执失败 | 最近一次回执（errCode≠0）晚于下发时间 |

> 协议回执**不带车牌**，所以「确认」只能按时间先后推断，页面上也写明了这一点 —— 宁可承认是推断，也不要给个假的「已下发成功」。

实测（入口相机）：`京A12345` `pushedAt=2026-11-26 12:02:40`、`CONFIRMED`，最近回执 12:02:40 成功。

### 17.3 「最近一次失败回执」提示

`GET /api/admin/vehicle/mqtt/last-failure[?deviceSn=]` → `{failure, reply}`：

- `failure`：最近一条 `下行回执：失败 errCode=… errInfo=…`（没有则 `null`）
- `reply`：最近一条回执（成功/失败都算）

车闸对接页顶部直接显示，并说明「平台已清掉该设备的对账状态、20 秒内自动整体重推」，不用再去日志表里翻。

### 17.4 强制同步支持「全部车闸」

`POST /api/admin/vehicle/mqtt/force-resync-all`：逐台在线相机执行「ClearData → 全量重推 → 补发 DlPassRule」，返回 `{deviceTotal, succeeded, devices[{deviceSn,status,error}]}`。

- 离线的相机跳过并给原因（指令发出去它也收不到，不如让人到现场）
- 一台失败不影响其它台

实测：两台相机 `CLEARED`，`vehicle_mqtt_plate_state` 各 1 条、回执成功、`OverUpdate{passruler:1}` 正常。

### 17.5 行人终端「残留名单清理」（补偿）

**背景**：PULL 协议既没有「清空名单」指令，也读不到设备名单。绕过停职直接改库、或现场在设备上登记过人之后，终端会留平台不认的人（此前出现过终端残留 8 人）。

**做法**：只能做差集 —— `person_device_state(EXPECT_IN)` − `access_grant(status=1 且在有效期内)`：

```
POST /api/admin/device/{id}/person-cleanup
→ {deviceSn, expected, tracked, removed[], failed[], missing[]}
```

- `removed`：差集里（平台下发过、现在不该在）逐个 `removePerson` 成功的人
- `failed`：设备拒绝/离线
- `missing`：平台认为该在、但没有下发记录的人 —— 提示用「员工 → 同步全部」重推

**配套修复**：员工派发原先不写 `person_device_state`，导致员工残留永远查不出来。`EmployeeDispatchService.push/remove` 现在也写对账记录。

**实测**：

| 轮次 | 结果 |
|---|---|
| 第一次（清理前 `tracked=2`, `expected=1`） | `removed=[V202611240001, V202611250001]`（历史测试残留），`failed=[]` |
| 员工 `sync-all` 后 | `person_device_state` 出现 `E000001(EXPECT_IN)`，`E000002(EXPECT_OUT)` |
| 第二次 | `removed=[] missing=[]`（幂等） |

### 17.6 日志轮转：只保留 15 天

| 对象 | 配置 |
|---|---|
| `/opt/fangke/logs/*.log` | `/etc/logrotate.d/fangke`：`daily`、`rotate 15`、`compress`、`delaycompress`、`copytruncate`、`dateext`、`su root root` |
| nginx 自带日志 | `/etc/logrotate.d/nginx` 的 `rotate 14 → 15` |
| journald | `MaxRetentionSec=15day`、`SystemMaxUse=500M` |
| DB `vehicle_mqtt_log` | `fangke.job.vehicle-mqtt-log-retention-days` 30 → **15** |
| DB `job_run_log` | `fangke.job.job-log-retention-days` 30 → **15** |
| docker（mysql/minio） | 已是 `max-size=10m, max-file=3`，不会无限增长 |

> **为什么必须用 `copytruncate`**：java（systemd `StandardOutput=append:`）和 nginx 都**持着文件 fd**，`mv` 走的老写法会让它们继续往已改名的归档文件里写，`stdout.log` 永远是空的。

实测：`logrotate -f /etc/logrotate.d/fangke` 后生成 `stdout.log-20261126`、`stderr.log-20261126`、`mqtt-capture.log-20261126`、`access.log-20261126`，新 `*.log` 归零，`systemctl is-active fangke-visitor` = active、nginx `http://127.0.0.1/` = 200。

## 18. 备份（P0）：每日 MySQL + MinIO，本机保留 14 天

上线前最大的空白是「服务器没有任何备份」——误删一条员工/一次清库、或 MinIO 里照片丢了，都只能重来。本轮补上（客户确认：**先只放本机**）。

### 18.1 组成

| 部件 | 说明 |
|---|---|
| `/opt/fangke/bin/backup.sh` | 备份脚本，root:root 700 |
| `fangke-backup.service` | `Type=oneshot`，跑脚本；`Nice=10` + `IOSchedulingClass=idle`，白天绝不会抢业务 IO |
| `fangke-backup.timer` | `OnCalendar=*-*-* 03:10:00`、`Persistent=true`（关机错过会在开机后补跑）、`RandomizedDelaySec=300` |
| `/opt/fangke/backup/` | 产物目录，root 700 —— 应用账户 `ubuntu` **改不到**备份，删库也删不掉备份 |
| `/opt/fangke/logs/backup.log` | 每天一份记录，已被 §17 的 logrotate 覆盖（15 天） |

### 18.2 备份内容

1. **MySQL 全库**：`mysqldump --single-transaction --quick --default-character-set=utf8mb4 --databases fangke`，密码走容器内的 `MYSQL_ROOT_PASSWORD` 环境变量（脚本里不落明文）；`--single-transaction` 是一致性快照，不锁业务表
2. **MinIO 照片**：`tar -czf ... -C /opt/fangke minio-data`（容器 `/data` 就是 `/opt/fangke/minio-data` 这个 bind mount）
3. 两个产物都做完整性校验（`gzip -t` / `tar -tzf`），任何一步失败脚本直接 `exit 1`，systemd 把服务标成 `failed`

### 18.3 首次实测（2026-11-26 12:10）

| 项 | 结果 |
|---|---|
| `systemctl start fangke-backup.service` | `status=0/SUCCESS` |
| db 产物 | `fangke-20261126-1210.sql.gz` 1.5M（46 张表） |
| media 产物 | `media-20261126-1210.tar.gz` 608K |
| **恢复演练** | 解压导入临时库 `fangke_restore_test` → 46 张表、`employee` 2 行、`device` 3 行，与原库一致；演练库已删 |
| 定时器 | `NEXT Fri 2026-11-27 03:11:36 CST` |
| 磁盘 | 备份目录所在根分区 69G / 已用 8.1G，14 份备份远不到 1%（照片增长到 GB 级再考虑增量方案） |

### 18.4 怎么查 / 怎么恢复

```bash
# 看今天有没有备份、有没有失败
systemctl list-timers fangke-backup.timer --no-pager
systemctl status fangke-backup.service --no-pager
sudo tail -n 30 /opt/fangke/logs/backup.log

# 恢复数据库（覆盖现有库，会先被镜像里的 mysql 覆盖写入）
gunzip -c /opt/fangke/backup/db/fangke-YYYYmmdd-HHMM.sql.gz | \
  docker exec -i fangke-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'

# 恢复照片
sudo tar -xzf /opt/fangke/backup/media/media-YYYYmmdd-HHMM.tar.gz -C /opt/fangke

# 重启应用
sudo systemctl restart fangke-visitor

# 手工立即备份一次（改完重要数据/大版本上线前）
sudo systemctl start fangke-backup.service
```

> **边界**：备份与业务在同一台服务器上。它能救「误删、误改、单表写坏、照片被覆盖」，救不了「整台机器/云盘挂掉」。需要异地副本时，把 `/opt/fangke/backup/` 的产物同步到对象存储或另一台机器即可，脚本不用改。
