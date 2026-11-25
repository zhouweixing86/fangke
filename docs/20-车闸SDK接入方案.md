# 20 · 车闸相机 SDK 接入方案（信路通 HvDevice v2.2.2.115）

> 开发包：`linux64_win32_win64_HvDevice(v2.2.2.115)_SDK开发包`（现场提供）
> 厂商：深圳信路通智能技术有限公司（Signaltone）
> 对应设备：出口相机 `COPCM-E10-LS26050994`，设备类型 **`PCM500FSPUTV14`（蜂晶系列）**，固件 `21.2.0.3045`
> 开发包里**正好有 `HvDeviceDemo(c#)\PCM500FSPUTV14(蜂晶系列)` 的示例**，可直接照抄

---

## 1. 这个 SDK 能做什么（已核对头文件与说明书）

| 能力 | API | 说明 |
|---|---|---|
| 连相机 | `HVAPI_OpenEx(ip, apiVer)` / `HVAPI_CloseEx` | 按 IP 直连，**不需要账号密码**；apiVer 传 NULL 用默认 |
| 识别结果回调 | `HVAPI_SetCallBackEx` + `HVAPI_StartRecvResult` | 回调类型：`CALLBACK_TYPE_RECORD_PLATE`（车牌）、`..._BIGIMAGE/_SMALLIMAGE/_BINARYIMAGE`（大图/小图/二值图）、`..._INFOBEGIN/INFOEND` |
| 白名单·V1（**本机实测可用**） | `HVAPI_InportNameList` / `GetNameList` / `AddNameList` / `DelNameList` / `QueryNameList` | 字符串格式：`车牌-有效期(YYYYMMDD)$车牌-有效期$...`（**每个条目必须带 `$`**，GBK 编码） |
| 白名单·V2 | `HVAPI_ImportNameListV2` / `AddNameListV2` / `GetNameListV2` / `DelNameListV2` | 结构体 `CWhiteListInfo`（车辆类型/时间段/备注，UTF-8）——**本机固件 21.2.0.3045 实测不支持，全部返回 E_FAIL**，其它型号可能支持 |
| 停车场车牌名单 | `HVAPI_SavePlateListToCamera` / `ReadPlateListInfo` / `DeletePlateList` / `DeleteAllPlateList` | 结构体 `PLATE_LIST_INFO{车牌, 起始时间, 结束时间, nFlag}` |
| 参数/命令 | `HVAPI_SetParamEx` / `GetParamEx` / `ExecCmdEx` | XML/JSON 命令通道（改设备参数、屏显等）；平台协议配置项未在手册里单列 |

`CWhiteListInfo`（V2 结构体）：

```c
typedef struct CWhiteListInfo {
  char chPlateNo[32];      // 车牌
  EnVehicleType enType;    // 车辆类型
  char chCreateTime[32];   // 创建时间 2026-01-01 00:00:01
  char chStartTime[32];    // 有效开始
  char chEndTime[32];      // 有效结束
  int  nTimeSegEnable;     // 时间段开关
  char chSegStart[32];     // 08:00
  char chSegEnd[32];       // 20:00
  ...
} CWhiteListInfo;

enum EnVehicleType {
  VEH_MONTHLY_RENT = 1, VEH_PROPERTY_RIGHT = 2, VEH_STORED_VALUE = 3, VEH_VIP = 4,
  VEH_BLACKLIST = 5, VEH_CUSTOM_WHITELIST = 6, VEH_CUSTOM_BLACKLIST = 7, VEH_OFFLINE_PASS = 9
};
```

**结论**：SDK 完整支持我们需要的两件事——**下发/更新/删除带有效期的白名单** 和 **实时拿识别结果**，
不需要相机「标准协议」的报文文档。

---

## 2. 关键约束：SDK 是客户端库，必须跑在能访问相机的机器上

相机在客户内网 `192.168.10.152`，云服务器 `58.87.99.249` **连不到它**（私网）。
所以要在现场放一台常开机器（工控机 / 现有管理电脑 / 小主机）跑「**边缘网关**」：

```
人行走廊终端（心跳）          云平台 58.87.99.249                现场边缘网关               相机 192.168.10.152
   └── /openapi/v1/* ───────►  Spring Boot + MySQL ◄─── 拉车牌清单（定时）── Java+JNA ──► HvDevice.dll
                               （现有系统）        ◄── 推识别结果（HTTP）── 回调 StartRecvResult
```

- **下发**：边缘网关定时 `GET /api/edge/vehicle/plates`（云平台出「应下发清单」+ 版本号）→ 本地比对 →
  `AddNameListV2`（新增/更新）+ `DelNameListV2`（删除）；首次可 `ImportNameListV2` 全量对齐。
- **上报**：SDK 车牌回调 → 边缘网关 `POST /api/edge/vehicle/event`（复用云平台现有 `/openapi/v1/vehicleEvent` 的判定/入库逻辑）。
- **不动相机现有平台**：SDK 与相机「标准协议(MQTT)」是两条独立连接（设备信息里「结果链接 0/5」），
  理论上前者不影响后者；**这点联调时要验证**（接上后看现有平台是否仍在线）。

---

## 3. 与其它两条路线对比

| | A. 云车场 openAPI（docs/19） | B. 相机标准协议（切 HTTP 推送） | **C. SDK + 边缘网关（本文）** |
|---|---|---|---|
| 依赖 | 车场 park_id/secret，付费开通 | 相机协议文档（缺） | 只需相机可达 + 边缘机器 |
| 需要改相机配置 | 不用 | **要改**，断开现有平台 | **不用**（SDK 另开连接） |
| 白名单 | 车场平台负责下发 | 取决于协议 | **SDK 直接下发（带有效期）** |
| 识别结果 | 车场平台转发 | 相机推送 | **SDK 回调直接拿** |
| 额外硬件 | 无 | 无 | 现场一台常开机器 |
| 费用 | 接口付费 | 无 | 无 |

---

## 5. 实测（2026-11-25，Windows + JDK17 + JNA 5.13，直连 `192.168.10.152`）

用开发包里的 `HvDevice.dll` 写了 POC（源码在 `private/hv-sdk-poc/`，未入库）实测：

| 调用 | 结果 |
|---|---|
| `HVAPI_GetDevTypeEx` | `ret=0` |
| `HVAPI_OpenEx(ip, NULL)` | 返回有效句柄（**不需要账号密码**） |
| `HVAPI_GetNameList` | `ret=0`，白名单为空（与 Web 后台「脱机白名单 → 暂无数据」一致） |
| `HVAPI_AddNameList("京A00000-20261231")` | `ret=0` **但读回为空** ← 少了结尾 `$`，静默不生效 |
| `HVAPI_AddNameList("京A00000-20261231$")` | `ret=0`，`GetNameList` 立刻读回 `京A00000-20261231$` ✅ |
| `HVAPI_DelNameList("京A00000$")` | `ret=0`，读回为空 ✅（Web 后台也恢复「暂无数据」，测试数据已清理） |
| `HVAPI_ImportNameListV2 / AddNameListV2 / GetNameListV2 / DelNameListV2` | 全部返回 `E_FAIL(0x80004005)` —— **该固件（21.2.0.3045）不支持 V2** |
| `HVAPI_GetNameListAvailablePeriod` | `E_FAIL`（不支持） |

**踩到的坑与结论**：

1. 名单字符串**每个条目必须以 `$` 结尾**（`车牌-YYYYMMDD$车牌2-YYYYMMDD$`）。缺 `$` 时接口返回成功但**什么都没写**，很难发现。
2. V1 名单编码是 **GBK/ANSI**（不是 UTF-8），有效期只到**日**（`YYYYMMDD`）。
3. V2（`CWhiteListInfo`，带车辆类型/时间段/备注）在这台相机上不可用 → 只用 V1；
   车辆类型由相机「脱机白名单 → 控制模式」按类型决定（当前：白名单=脱机后自动开闸）。
4. Web 后台的「脱机白名单」列表与 SDK 操作的是**同一份数据**，可用来核对。
5. SDK 连接与相机现有「标准协议(MQTT)」是两条独立连接（设备信息里「结果链接 0/5」），实测连接/读写期间现有平台仍在线。

## 6. 待确认（决定能否落地 C）

1. **现场有没有常开的机器**能跑边缘网关（Windows 10/11 或 Linux x64 都行，Java 17 一处构建两边跑）？还是需要新买一台小主机/工控机？
2. 相机是否允许多客户端同时连（手册显示结果链接上限 5，现在 0）；接上后现有 MQTT 平台是否仍正常（实测短连接无影响，长期运行待观察）。
3. 白名单**容量上限**（车位数/名单条数）与**车牌匹配模式**（当前「完全匹配」）。
4. 员工/访客车牌是否都进同一个白名单（V1 没有车辆类型，只有车牌+到期日；如需要类型区分只能靠 V2，本机不支持）。

## 7. 建议的落地步骤

1. 边缘网关（Java 17 + JNA）：
   - `HVAPI_OpenEx` 长连接 + 断线重连；
   - 名单：`HVAPI_GetNameList` 读回 → 与云平台快照 diff → `AddNameList`/`DelNameList`（格式 `车牌-YYYYMMDD$`）；
     首次可直接 `HVAPI_InportNameList` 全量覆盖；
   - 识别结果：`HVAPI_SetCallBackEx` + `HVAPI_StartRecvResult`（`CALLBACK_TYPE_RECORD_PLATE` + 图片）→ POST 云平台；
2. 云平台加 `/api/edge/**`（边缘令牌）与「车牌下发通道 = EDGE」开关，复用现有 `vehicle_plate_push` 差分逻辑；
3. 联调：加一个测试车牌 → 边缘日志 `AddNameList OK` → 相机「脱机白名单」出现该车牌 → 车过 → 云平台收到识别记录；
4. 验证删除、到期日变更、相机离线重连、云平台重启等边界。
