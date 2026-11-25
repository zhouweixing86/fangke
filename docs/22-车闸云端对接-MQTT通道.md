# 22 · 车闸云端对接（MQTT 通道，C-87）

> 目标（客户明确要求）：**闸机设备连到我们云服务器，由我们平台下发车牌 → 相机识别开门，也能删除车牌；
> 现场不装电脑，只有网线能上网。**
>
> 实现方式：相机作为 MQTT 客户端连我们云上的 broker（`upload/{SN}` 上行、`download/{SN}` 下行），
> 平台侧新增「车闸对接」页面与 `/api/admin/vehicle/mqtt/**` 接口。

---

## 1. 为什么是 MQTT

| 方案 | 现场要电脑 | 相机要公网 IP | 现状 |
|---|---|---|---|
| 云端直连相机 SDK（TCP 9910） | 不要 | **要**（端口映射/VPN） | SDK 白名单增删**已实测可用**，但网络不可达时用不了 |
| 相机标准协议（HTTP 推送） | 不要 | 不要 | 只解决上行；下行（下发白名单）没有入口，且实测客户端未启动 |
| **MQTT：相机主动连云** | **不要** | **不要** | ✅ 本次实现；相机是客户端，天然穿透 NAT |

现场「有网线能上网」正好满足 MQTT 方案；相机不需要公网 IP，平台也不需要能连到相机。

> ⚠️ 实测发现：相机走 **4G** 时，运营商侧会重置 MQTT 连接（RST 的 TTL 与相机包不一致）。
> **必须走有线网络**（现场网线），或换 TLS/其他承载。办公网出口实测 MQTT 正常。

## 2. 架构

```
现场：相机（MQTT 客户端）─── 网线 ──► 公网
                                    │  publish: upload/COPCM-E10-xxxx
                                    ▼
云端：Mosquitto(1883, 账号 fangke) ──► Spring Boot「车闸 MQTT 网关」
        ▲                                   ├─ 订阅 upload/+ → 解析车牌 → 车辆通行记录/判定
        └── publish: download/{SN} ◄────────┤ 下发车牌（AddCarInfo / DeleteCarInfo）
             （固定车白名单增/删）             └─ 管理页「车闸对接」下发/删除/全量对齐/报文日志
```

> 报文格式已按厂商《信路通路外停车 MQTT 协议接口规范 V14》实机核对，详见 [23-车闸MQTT标准协议与实机联调.md](./23-车闸MQTT标准协议与实机联调.md)。

## 3. 相机侧配置（一次配好，之后不用动）

Web 后台 `http://<相机IP>/pc/` → 高级设置 → 平台配置 → **标准协议（MQTT）**：

| 字段 | 值 |
|---|---|
| 标准协议（MQTT）使能 | 开 |
| 平台地址 | `58.87.99.249` |
| 云平台端口 | **1883**（腾讯云防火墙需放行；临时也开了 1833） |
| 云平台用户名 / 密码 | `fangke` / 见 `private/腾讯云部署凭据.md` |
| 客户端ID | `{DevSerialNum}`（保持默认） |
| 自定义发布主题 | `upload/{DevSerialNum}`（保持默认） |
| 自定义订阅主题 | `download/{DevSerialNum}`（保持默认） |
| Rtd 心跳间隔 | 5 秒 |
| 保存 | 点右上「保存所有」 |

> **开门策略**：高级设置 → 脱机白名单 → 「白名单」控制模式选 **自动开闸**（当前是「脱机后自动开闸」，
> 那样只有相机离线时才自动开）。这样相机识别到名单内车牌就直接开闸，名单由平台 MQTT 下发/删除。

设备还需要在平台「设备管理 → 车闸相机」里登记（SN = 相机的 `COPCM-...`），
这样全量对齐/通行记录才能关联到门点；单条下发可以直接手输 SN。

## 4. 平台侧配置（`/opt/fangke/.env`）

```bash
VEHICLE_MQTT_ENABLED=true
VEHICLE_MQTT_BROKER_URL=tcp://127.0.0.1:1883
VEHICLE_MQTT_USERNAME=fangke
VEHICLE_MQTT_PASSWORD=<broker 密码>
# 主题可按现场改
VEHICLE_MQTT_UPLOAD_TOPIC=upload/+
VEHICLE_MQTT_DOWNLOAD_PREFIX=download/
# 抓到的真实报文如果字段变了，用模板覆盖即可，不用改代码
# 可替换占位：${plate} ${type} ${begin} ${end} ${endDate} ${sn} ${requestId} ${time} ${items}
# VEHICLE_MQTT_ADD_TEMPLATE={"command":"AddCarInfo","data":"{\"carInfoList\":[{\"plate\":\"${plate}\",\"type\":${type},\"time\":\"${endDate}\"}]}","requestId":"${requestId}","sn":"${sn}","time":"${time}","version":"1.0.1"}
# VEHICLE_MQTT_DELETE_TEMPLATE={"command":"DeleteCarInfo","data":"{\"plateList\":[\"${plate}\"]}","requestId":"${requestId}","sn":"${sn}","time":"${time}","version":"1.0.1"}
# 固定车类型：1 月租车（按日期过期，访客默认）/ 4 贵宾车（实测相机存「无」=永不过期）
VEHICLE_MQTT_PLATE_TYPE=1
```

改完 `systemctl restart fangke-visitor`。

## 5. 平台接口与页面

| 接口 | 作用 |
|---|---|
| `GET /api/admin/vehicle/mqtt` | 连接状态、主题、当前报文模板 |
| `POST /api/admin/vehicle/mqtt/reconnect` | 重连 broker |
| `POST /api/admin/vehicle/mqtt/enable-reply?deviceSn=` | 使能下行回执（相机之后每条下行都回 `errCode`） |
| `POST /api/admin/vehicle/mqtt/plate` | 下发/更新车牌：`{deviceSn, plateNo, beginTime, endTime}` |
| `DELETE /api/admin/vehicle/mqtt/plate?deviceSn=&plateNo=` | 删除车牌 |
| `POST /api/admin/vehicle/mqtt/raw` | 发原始报文（协议探测） |
| `POST /api/admin/vehicle/mqtt/import?gateId=` | 把「员工 + 当天已授权访客」车牌全量推给所有车闸 |
| `GET /api/admin/vehicle/mqtt/logs?limit=` | 最近上下行报文 |
| `DELETE /api/admin/vehicle/mqtt/logs` | 清空日志 |

页面：**基础资料 → 车闸对接**（菜单权限沿用 `device:read`），可下发/删除车牌、全量对齐、发原始报文、看上下行日志。

## 6. 报文与协议

相机用的是厂商**标准协议（MQTT）**：公共报文头 `{command, data, requestId, time, version, sn}`，
且 **`data` 必须是把业务 JSON 序列化成字符串后的值**（协议原文要求）。

白名单在协议里叫「固定车 CarInfo」，不是本地接口的 `AddNameList`（那个只在本机 HTTP/XML 与 SDK 里存在）：

```json
// 下发/更新车牌（topic download/{SN}）
{"command":"AddCarInfo","data":"{\"carInfoList\":[{\"plate\":\"苏A12345\",\"type\":1,\"time\":\"2026-12-31\"}]}","requestId":"1795622980551","sn":"COPCM-E10-LS26050994","time":"2026-11-26 00:09:40","version":"1.0.1"}

// 删除车牌
{"command":"DeleteCarInfo","data":"{\"plateList\":[\"苏A12345\"]}","requestId":"1795622989287","sn":"COPCM-E10-LS26050994","time":"2026-11-26 00:09:49","version":"1.0.1"}

// 使能回执（相机 Conn 上行后平台自动下发）
{"command":"EnableReply","data":"{\"enable\":1}","requestId":"1795622137258","sn":"COPCM-E10-LS26050994","time":"2026-11-25 23:55:37","version":"1.0.1"}

// 相机上行：心跳 / 回执 / 识别结果
{"command":"Rtd","data":"{\"hasCar\":2}","requestId":"...","sn":"...","time":"...","version":"1.0.1"}
{"command":"AddCarInfo","data":"{\"errCode\":0,\"errInfo\":\"\"}","requestId":"<与下行同值>","sn":"...","time":"...","version":"1.0.1"}
{"command":"Result","data":"{\"carNO\":\"苏A12345\",\"carNOC\":\"蓝\",\"confid\":885,\"openGate\":0,...}",...}
```

要点：

- **V1 优先**：`AddCarInfoV2/QueryCarInfoV2/DlCarInfoV2` 在本机固件（APP `21.1.22.15541_20260408`）回
  `errCode=1 / "Unknow CMD."`，所以默认走 V1；换新固件可只改模板切 V2。
- **回执**：`EnableReply` 之后每条下行相机都回同名 command + `{errCode, errInfo}`，`requestId` 与下行一致；
  平台把 `errCode=0` 记成「下行回执：成功」。
- **类型**：`plateType` 默认 1（月租车，按 `time` 日期过期）。实测 4（贵宾车）相机会把到期时间存成「无」=永不过期。
- **上行解析**：先找 `data` 里的 `carNO`（协议字段），再兼容 `plateNo/plate/plateNumber/carNo/license`，最后正则兜底；
  解析不到只留痕不报错。相机若开了车牌加密，`carNO` 是密文，日志会提示「车牌已加密」。
- 协议探测：页面「原始报文」直接发；所有上下行都会写 `vehicle_mqtt_log`（大图截断 20KB）。

## 7. 已验证

### 7.1 平台侧（2026-11-25 22:28–22:31，模拟设备）

| 项 | 结果 |
|---|---|
| MQTT 网关连接 broker | ✅ `connected=true`（客户端 ID `fangke-vehicle-gateway`） |
| 平台下发车牌 | ✅ `POST /mqtt/plate` → broker 抓到 `download/TEST-SN` 报文 |
| 上行识别结果 | ✅ 模拟 `upload/TEST-SN` → 平台解析出车牌、判定、写入 `vehicle_event` |
| 迁移 | ✅ Flyway V35 `vehicle_mqtt_log` |

### 7.2 真机闭环（2026-11-25 23:39–2026-11-26 00:10）

| 步 | 结果 |
|---|---|
| 相机连云 broker | ✅ 每 5s 心跳 `Rtd`（`hasCar:2`）+ `Image`，`EnableReply` 回执 `errCode=0` |
| 平台下发车牌 | ✅ `AddCarInfo` → 回执 `errCode=0` → 相机「脱机白名单」读到 `苏A12345`（月租车，到期 2026-12-31 23:59:59） |
| 平台删除车牌 | ✅ `DeleteCarInfo` → 回执 `errCode=0` → 白名单 `totalCnt=0` |
| 相机网络 | ✅ 改 DHCP 后插电脑共享网口自动拿到 `192.168.137.166`，出网 IP 为办公网 `117.89.73.58` |
| 半死连接 | ⚠️ 出现过 Paho 心跳超时后不再自动重连、下发卡到 nginx 504；已加**看门狗**（每 30s 检查并重建连接）+ 发布前判活 |
| 单测 | ✅ 264 全绿 |

> 剩下的唯一不确定项：**相机真实下行报文格式**。它不影响架构，只要现场连上后看「车闸对接」日志，
> 把模板调成真实格式即可（或在 log 里直接把相机对平台指令的回执贴给我）。

## 8. 备选：SDK 直连（相机有公网可达时）

开发包 `HvDevice v2.2.2.115`（信路通）实测可用：

| API | 作用 |
|---|---|
| `HVAPI_OpenEx(ip, NULL)` | 连相机（TCP **9910**，无需账号密码） |
| `HVAPI_GetNameList` | 读白名单 |
| `HVAPI_AddNameList("京A00000-20261231$")` | 增/改车牌（**每条必须 `$` 结尾**，GBK，日期 YYYYMMDD） |
| `HVAPI_DelNameList("京A00000$")` | 删除 |
| `HVAPI_*NameListV2` | 该固件（21.2.0.3045）不支持，返回 E_FAIL |
| `HVAPI_SetCallBackEx` + `HVAPI_StartRecvResult` | 识别结果回调（车牌/大图/小图） |

`libHvDevice.so` 在云服务器（Ubuntu 24.04 / glibc 2.39 / JDK17）**依赖全部满足**，
只要现场把相机 **TCP 9910** 映射到公网（或 VPN），云端就能直连下发，不需要相机连云。
POC 源码在 `private/hv-sdk-poc/`，厂商接口手册在 `private/vendor-docs/`。

## 9. 上线检查清单

- [ ] 腾讯云防火墙放行 **TCP 1883**（相机连云）
- [ ] `.env` 里 `VEHICLE_MQTT_ENABLED=true` + broker 账号密码
- [ ] 相机「标准协议(MQTT)」按 §3 配好并保存
- [ ] 设备管理里登记车闸相机（SN、门点、方向）
- [ ] 「车闸对接」页显示 **MQTT 已连接**
- [ ] 下发一条测试车牌 → 相机「脱机白名单」里能看到 → 车过开门
- [ ] 删除该车牌 → 相机名单消失
- [ ] 抓一份真实上下行报文，必要时调整模板
