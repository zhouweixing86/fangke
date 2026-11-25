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

## 7. 平台实现映射

| 协议要素 | 代码/配置 |
|---|---|
| 信封与模板 | `VehicleMqttService.DEFAULT_ADD/DELETE/IMPORT`、`AppProperties.Vehicle.Mqtt.*Template`、`application.yml` `fangke.vehicle.mqtt.*` |
| 固定车类型 | `fangke.vehicle.mqtt.plate-type` / `VEHICLE_MQTT_PLATE_TYPE`（默认 1） |
| 回执使能 | `VehicleMqttService.enableReply`（收到 `Conn` 自动下发）+ `POST /api/admin/vehicle/mqtt/enable-reply` |
| 回执解析 | `describeWithoutPlate`：`errCode=0` → 「下行回执：成功」 |
| 识别结果解析 | `extractPlate`：`data` 是字符串也能解析，字段优先 `carNO` |
| 连接可靠性 | `VehicleMqttGateway.ensureConnected` 看门狗（默认 30s，`fangke.vehicle.mqtt.watchdog-ms`）+ `publish` 前判活 |
| 日志降噪 | 心跳 `Rtd` 与无车 `Image` **不写** `vehicle_mqtt_log`（5s 一条会撑爆库），只打 debug |
| 管理端 | 「基础资料 → 车闸对接」：状态/重连/使能回执/下发/删除/全量对齐/原始报文/上下行日志 |

## 8. 现场落地清单

1. 相机接**能上网的网线**（现场无电脑亦可），网络设为 **DHCP**（本机已验证）；
2. 相机「高级设置 → 平台配置 → 标准协议（MQTT）」：地址 `58.87.99.249`、端口 `1883`、账号 `fangke`、
   客户端 ID `{DevSerialNum}`、发布 `upload/{DevSerialNum}`、订阅 `download/{DevSerialNum}`、心跳 5s；
3. 「脱机白名单」里白名单控制模式选**自动开闸**（否则识别到也不抬杆）；
4. 平台「设备管理 → 车闸相机」登记该 SN，之后访客/员工车牌按业务自动下发（也可在「车闸对接」手工下发）；
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
| 平台下发 504 / 卡住 | MQTT 半死连接（旧版本） | 升级到含看门狗的版本，或点「重连」 |
| 相机连上就断 | 走 4G | 改有线；4G 出口会被中间设备重置 |
