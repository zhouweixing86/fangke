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
        └── publish: download/{SN} ◄────────┤ 下发车牌（AddNameList）
             （白名单增/删）                  └─ 管理页「车闸对接」下发/删除/全量对齐/报文日志
```

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
# 抓到真实报文后，用模板覆盖即可，不用改代码
# VEHICLE_MQTT_ADD_TEMPLATE={"CmdName":"AddNameList","Data":{"plateNumber":"${plate}","beginTime":"${begin}","endTime":"${end}"}}
# VEHICLE_MQTT_DELETE_TEMPLATE={"CmdName":"DelNameList","Data":{"plateNumber":"${plate}"}}
```

改完 `systemctl restart fangke-visitor`。

## 5. 平台接口与页面

| 接口 | 作用 |
|---|---|
| `GET /api/admin/vehicle/mqtt` | 连接状态、主题、当前报文模板 |
| `POST /api/admin/vehicle/mqtt/reconnect` | 重连 broker |
| `POST /api/admin/vehicle/mqtt/plate` | 下发/更新车牌：`{deviceSn, plateNo, beginTime, endTime}` |
| `DELETE /api/admin/vehicle/mqtt/plate?deviceSn=&plateNo=` | 删除车牌 |
| `POST /api/admin/vehicle/mqtt/raw` | 发原始报文（协议探测） |
| `POST /api/admin/vehicle/mqtt/import?gateId=` | 把「员工 + 当天已授权访客」车牌全量推给所有车闸 |
| `GET /api/admin/vehicle/mqtt/logs?limit=` | 最近上下行报文 |
| `DELETE /api/admin/vehicle/mqtt/logs` | 清空日志 |

页面：**基础资料 → 车闸对接**（菜单权限沿用 `device:read`），可下发/删除车牌、全量对齐、发原始报文、看上下行日志。

## 6. 报文与协议

默认按相机命令风格（`CmdName + Data`，与厂商 SDK 文档里 LCD 命令同风格）：

```json
// 下行（我们 → 相机，topic download/{SN}）
{"CmdName":"AddNameList","Data":{"plateNumber":"京A12345","beginTime":"2026-11-26 08:00:00","endTime":"2026-11-26 20:00:00"}}
{"CmdName":"DelNameList","Data":{"plateNumber":"京A12345"}}
{"CmdName":"ImportNameList","Data":{"items":[{"plateNumber":"京A12345","beginTime":"...","endTime":"..."}]}}

// 上行（相机 → 我们，topic upload/{SN}）
{"plateNumber":"京A12345","inout":1,"time":"2026-11-26 09:12:33"}
```

- 上行解析兼容多种字段：`plateNumber/plateNo/plate/license/carNo`，解析失败会退化为正则从原文提车牌；
  仍然解析不出时**只留痕不报错**（报文可在「车闸对接」日志里看到）。
- **如果现场抓到的真实报文不是这个格式**：把 `VEHICLE_MQTT_ADD_TEMPLATE` / `DELETE_TEMPLATE`
  按真实报文改掉（支持 `${plate}` `${begin}` `${end}` `${sn}` `${items}`），不用改代码、不用重新发版。
- 协议探测：页面「原始报文」直接发；所有上下行都会写 `vehicle_mqtt_log`（大图截断 20KB）。

## 7. 已验证（2026-11-25 22:28–22:31，生产服务器）

| 项 | 结果 |
|---|---|
| MQTT 网关连接 broker | ✅ `connected=true`（客户端 ID `fangke-vehicle-gateway`） |
| 平台下发车牌 | ✅ `POST /mqtt/plate` → broker 抓到 `download/TEST-SN` 报文（内容为模板渲染结果） |
| 平台删除车牌 | ✅ 同一通道（`DelNameList` 模板） |
| 上行识别结果 | ✅ 模拟 `upload/TEST-SN` → 平台解析出车牌、判定「未登记车辆」、写入 `vehicle_event`（`event_uuid=MQTTe61e…`） |
| 迁移 | ✅ Flyway V35 `vehicle_mqtt_log` |
| 单测 | ✅ 261 全绿 |

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
