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
- 带车牌时会按车牌判定一次并把匹配到的人写进 `vehicle_event`，因此**会出现在该人当天的通行记录里**；
- 不带车牌只记一条「人工开闸」（不进按人汇总）；
- 操作人写进审计日志（`VEHICLE/MQTT_OPEN_GATE`）。

### 10.3 通行记录与照片

- 相机 `Result` 上行 → `vehicle_event`：车牌(`carNO`)、置信度(`confid`)、方向（按设备档案的进/出）、判定结果；
- 抓拍图：相机给的是 OSS 外链（`picN_full` / `picMin_full`），存 `vehicle_event.photo_url`；
  通行记录明细里直接显示缩略图（`photo_key` 那套是设备直传 base64 的旧通道，两者二选一）；
- **离线补传**：相机 `Conn` 后平台自动下发 `Config{enableReply:1, enableOffline:1}`
  （协议 §4.4.22）。不开的话，网络抖动期间识别到的车会**丢记录**，恢复后也不补；开了就会缓存补传。
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
