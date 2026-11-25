package com.fangke.visitor.vehicle.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.PlatePayload;
import com.fangke.visitor.vehicle.service.VehicleAccessService;
import com.fangke.visitor.vehicle.service.VehicleDeviceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车闸车牌 MQTT 下发与识别结果接收（docs/22）。
 *
 * <p>平台 → 相机：往 {@code download/{SN}} 发白名单增/删报文；
 * 相机 → 平台：订阅 {@code upload/+}，把识别结果落到车辆通行记录（复用现有判定逻辑）。
 *
 * <p>报文按相机「标准协议」的信封：{@code {command, data, requestId, sn, time, version}}，
 * 其中 {@code data} 是一段**被转义的 JSON 字符串**（实测相机上行就是这样，见 docs/23）。
 * 模板可在配置里改（{@code fangke.vehicle.mqtt.*-template}），换固件/换字段名时不用改代码。
 */
@Service
public class VehicleMqttService {

    private static final Logger log = LoggerFactory.getLogger(VehicleMqttService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 相机 `imgT` 的紧凑时间格式。 */
    private static final DateTimeFormatter COMPACT_DATETIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** V1 固定车的到期日只有日期。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 宽松车牌识别：汉字 + 字母 + 5~6 位字母数字。 */
    private static final Pattern PLATE_PATTERN =
            Pattern.compile("[京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新][A-Z][A-Z0-9]{4,6}");
    /** 相机 `Conn` 上行里的内网 IP：`devIP~192.168.1.4`。 */
    private static final Pattern DEVICE_IP =
            Pattern.compile("devIP~([0-9]{1,3}(?:\\.[0-9]{1,3}){3})");
    /** 心跳刷新在线状态的限流（SN → 最近一次刷新时间戳）。 */
    private final Map<String, Long> lastTouch = new java.util.concurrent.ConcurrentHashMap<>();

    /** 相机标准协议版本号（实测上行固定 1.0.1）。 */
    private static final String PROTOCOL_VERSION = "1.0.1";
    private static final String PROTOCOL_SUFFIX =
            "\"requestId\":\"${requestId}\",\"sn\":\"${sn}\",\"time\":\"${time}\",\"version\":\"" + PROTOCOL_VERSION + "\"}";

    /**
     * 增加固定车（增量，有则更新有效期、无则新增），见协议 §4.4.8。
     *
     * <p>白名单在 MQTT 标准协议里叫「固定车 CarInfo」，不是本地接口的 AddNameList。
     * V1 只有到期日（{@code time}），没有生效起始时间；V2 的 AddCarInfoV2 在
     * 实测固件（APP 21.1.22.15541_20260408）上回 {@code Unknow CMD}，所以默认用 V1。
     */
    private static final String DEFAULT_ADD = "{\"command\":\"AddCarInfo\",\"data\":\"{\\\"carInfoList\\\":["
            + "{\\\"plate\\\":\\\"${plate}\\\",\\\"type\\\":${type},\\\"time\\\":\\\"${endDate}\\\"}]}\","
            + PROTOCOL_SUFFIX;
    /** 删除固定车，见协议 §4.4.9（V1/V2 共用，实测可用）。 */
    private static final String DEFAULT_DELETE = "{\"command\":\"DeleteCarInfo\",\"data\":\"{\\\"plateList\\\":[\\\"${plate}\\\"]}\","
            + PROTOCOL_SUFFIX;
    /** 全量对齐：一次下发多条 AddCarInfo（比 DlCarInfo 需要托管 CSV 文件更简单）。 */
    private static final String DEFAULT_IMPORT = "{\"command\":\"AddCarInfo\",\"data\":\"{\\\"carInfoList\\\":[${items}]}\","
            + PROTOCOL_SUFFIX;
    /** 使能下行回执，见协议 §4.4.21：设备 Conn 上行后由平台下发，之后每条下行都会回 errCode。 */
    private static final String ENABLE_REPLY = "{\"command\":\"EnableReply\",\"data\":\"{\\\"enable\\\":1}\","
            + PROTOCOL_SUFFIX;
    /** 开闸，见协议 §4.4.2（data 为空串）。 */
    private static final String OPEN_GATE = "{\"command\":\"Open\",\"data\":\"\"," + PROTOCOL_SUFFIX;
    /**
     * 协议配置开关，见协议 §4.4.22：设备 Conn 后下发。
     *
     * <p>`enableReply=1` 让相机每条下行都回执；`enableOffline=1` 让它在断网期间缓存识别结果、
     * 恢复后补传（否则现场网络抖一下，那几辆车的通行记录就丢了）。
     */
    private static final String CONFIG_SWITCH =
            "{\"command\":\"Config\",\"data\":\"{\\\"enableReply\\\":1,\\\"enableOffline\\\":1}\"," + PROTOCOL_SUFFIX;


    private final AppProperties props;
    private final VehicleMqttGateway gateway;
    private final VehicleMqttLogRepository logRepository;
    private final VehicleAccessService accessService;
    private final VehicleDeviceService deviceService;
    private final com.fangke.visitor.vehicle.service.VehiclePlateService plateService;
    private final VehicleMqttPlateStateRepository stateRepository;

    /**
     * 专用于「回调线程里触发的下行」。
     *
     * <p>Paho 的 {@code messageArrived} 是接收线程回调，在里面直接 {@code publish} 会
     * 把接收线程堵住（PINGRESP 处理不了 → 30s 后判定 keepalive 超时 → 连接被杀、下发卡死），
     * 所以相机 {@code Conn} 上行后的 {@code EnableReply} 必须扔到别的线程发。
     */
    private final ExecutorService sideChannel = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vehicle-mqtt-side");
        thread.setDaemon(true);
        return thread;
    });

    public VehicleMqttService(AppProperties props, VehicleMqttGateway gateway,
                              VehicleMqttLogRepository logRepository,
                              VehicleAccessService accessService,
                              VehicleDeviceService deviceService,
                              com.fangke.visitor.vehicle.service.VehiclePlateService plateService,
                              VehicleMqttPlateStateRepository stateRepository) {
        this.props = props;
        this.gateway = gateway;
        this.logRepository = logRepository;
        this.accessService = accessService;
        this.deviceService = deviceService;
        this.plateService = plateService;
        this.stateRepository = stateRepository;
    }

    @PostConstruct
    public void registerListener() {
        gateway.setListener(this::onUpload);
    }

    @PreDestroy
    void shutdownSideChannel() {
        sideChannel.shutdownNow();
    }

    /* ---------------- 平台 → 相机 ---------------- */

    /** 新增/更新一条车牌（有效期到日）。 */
    public Map<String, Object> addPlate(String deviceSn, String plateNo, LocalDateTime begin, LocalDateTime end) {
        String plate = VehicleAccessService.normalize(plateNo);
        if (plate.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "车牌号不能为空");
        }
        String template = template(props.getVehicle().getMqtt().getAddTemplate(), DEFAULT_ADD);
        LocalDateTime endAt = end == null ? LocalDateTime.now().plusDays(1) : end;
        String payload = fill(template, envelopeValues(deviceSn, Map.of(
                "plate", plate,
                "type", String.valueOf(props.getVehicle().getMqtt().getPlateType()),
                "begin", DATETIME.format(begin == null ? LocalDateTime.now() : begin),
                "end", DATETIME.format(endAt),
                "endDate", DATE.format(endAt))));
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload, "下发/更新车牌 " + plate);
        return result("ADD", deviceSn, plate, payload);
    }

    /** 删除一条车牌。 */
    public Map<String, Object> deletePlate(String deviceSn, String plateNo) {
        String plate = VehicleAccessService.normalize(plateNo);
        if (plate.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "车牌号不能为空");
        }
        String template = template(props.getVehicle().getMqtt().getDeleteTemplate(), DEFAULT_DELETE);
        String payload = fill(template, envelopeValues(deviceSn, Map.of("plate", plate)));
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload, "删除车牌 " + plate);
        return result("DELETE", deviceSn, plate, payload);
    }

    /** 全量覆盖车牌（员工 + 当天已授权访客），用于对齐。 */
    public Map<String, Object> importPlates(String deviceSn, List<PlatePayload> plates) {
        int type = props.getVehicle().getMqtt().getPlateType();
        List<String> items = new ArrayList<>();
        for (PlatePayload plate : plates) {
            items.add(carInfo(plate.plateNo(), type,
                    plate.validTo() == null ? LocalDateTime.now().plusYears(10) : plate.validTo()));
        }
        String template = template(props.getVehicle().getMqtt().getImportTemplate(), DEFAULT_IMPORT);
        String payload = fill(template, envelopeValues(deviceSn, Map.of("items", String.join(",", items))));
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload, "全量下发 " + plates.size() + " 条车牌");
        // 同步更新对账状态：手动「全量对齐」之后，定时任务不应再把这批车牌重复推一遍；
        // 清单里没有的车牌把状态清掉，下一轮定时任务会补一条 DeleteCarInfo。
        Set<String> desired = new LinkedHashSet<>();
        for (PlatePayload plate : plates) {
            desired.add(plate.plateNo());
            stateRepository.upsert(deviceSn, plate.plateNo(), fingerprint(plate));
        }
        for (String existing : stateRepository.fingerprints(deviceSn).keySet()) {
            if (!desired.contains(existing)) {
                stateRepository.remove(deviceSn, existing);
            }
        }
        Map<String, Object> view = result("IMPORT", deviceSn, "", payload);
        view.put("count", plates.size());
        return view;
    }

    /** 使能下行回执（协议 §4.4.21）：设备 Conn 上行后下发，之后每条下行都回 errCode。 */
    public Map<String, Object> enableReply(String deviceSn) {
        String payload = fill(ENABLE_REPLY, envelopeValues(deviceSn, Map.of()));
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload, "使能下行回执");
        return result("ENABLE_REPLY", deviceSn, "", payload);
    }

    /** 下发协议配置开关（回执 + 离线结果补传），见协议 §4.4.22。 */
    public Map<String, Object> applyConfig(String deviceSn) {
        String payload = fill(CONFIG_SWITCH, envelopeValues(deviceSn, Map.of()));
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload, "配置：回执 + 离线结果补传");
        return result("CONFIG", deviceSn, "", payload);
    }

    /**
     * 人工远程开闸（协议 §4.4.2）。
     *
     * <p>传了车牌就按车牌判定一次，把匹配到的人写进通行记录（能和该员工的通行记录合并）；
     * 不传车牌只记一条「人工开闸」，不进按人汇总。
     */
    public Map<String, Object> openGate(String deviceSn, String plateNo, String operator) {
        Device device = deviceService.resolveOrRegister(deviceSn, null);
        Long gateId = device == null ? null : device.getGateId();
        int direction = device == null ? 0 : device.getDirection();
        String plate = plateNo == null ? "" : VehicleAccessService.normalize(plateNo);

        String payload = fill(OPEN_GATE, envelopeValues(deviceSn, Map.of()));
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload,
                "人工开闸" + (plate.isEmpty() ? "" : " " + plate) + (operator == null ? "" : "（" + operator + "）"));

        try {
            VehicleAccessService.Decision judged = plate.isEmpty() ? null : accessService.judge(plate, gateId, LocalDateTime.now());
            VehicleAccessService.Decision decision = new VehicleAccessService.Decision(true,
                    "人工远程开闸" + (operator == null ? "" : "（" + operator + "）"),
                    judged == null ? null : judged.matchedType(),
                    judged == null ? null : judged.matchedId(),
                    judged == null ? null : judged.matchedName(),
                    judged == null ? null : judged.personNo());
            accessService.record("MQA" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                    deviceSn, gateId, plate, direction, LocalDateTime.now(), null, decision, null, null, payload);
        } catch (Exception e) {
            log.warn("人工开闸记录写入失败 device={}：{}", deviceSn, e.getMessage());
        }
        Map<String, Object> view = result("OPEN", deviceSn, plate, payload);
        view.put("operator", operator == null ? "" : operator);
        return view;
    }

    /**
     * 把平台期望车牌同步到某台相机（差分）：新增/变更发 {@code AddCarInfo}，不在清单里的发 {@code DeleteCarInfo}。
     *
     * <p>这是「员工车牌 / 访客车牌自动下发、停用与到期自动删除」的落地点，由定时对账与相机上线触发。
     */
    public synchronized Map<String, Object> syncDevicePlates(String deviceSn, Long gateId) {
        List<PlatePayload> desired = plateService.snapshot(gateId);
        Map<String, String> existing = stateRepository.fingerprints(deviceSn);
        int added = 0;
        int updated = 0;
        int removed = 0;
        int unchanged = 0;
        Set<String> desiredPlates = new LinkedHashSet<>();
        for (PlatePayload plate : desired) {
            desiredPlates.add(plate.plateNo());
            String fingerprint = fingerprint(plate);
            String last = existing.get(plate.plateNo());
            if (fingerprint.equals(last)) {
                unchanged++;
                continue;
            }
            // 没写有效期的（员工长期车）给 10 年，别按「明天到期」下发
            LocalDateTime end = plate.validTo() == null
                    ? LocalDateTime.now().plusYears(10) : plate.validTo();
            addPlate(deviceSn, plate.plateNo(), plate.validFrom(), end);
            stateRepository.upsert(deviceSn, plate.plateNo(), fingerprint);
            if (last == null) {
                added++;
            } else {
                updated++;
            }
        }
        for (String plate : existing.keySet()) {
            if (!desiredPlates.contains(plate)) {
                deletePlate(deviceSn, plate);
                stateRepository.remove(deviceSn, plate);
                removed++;
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deviceSn", deviceSn);
        view.put("desired", desired.size());
        view.put("added", added);
        view.put("updated", updated);
        view.put("removed", removed);
        view.put("unchanged", unchanged);
        return view;
    }

    /** 相机是否可下发（开关打开且 broker 已连）。 */
    public boolean ready() {
        return props.getVehicle().getMqtt().isEnabled() && gateway.isConnected();
    }

    /** 车牌内容指纹：类型 + 起止时间，变了就重新下发（相机侧覆盖更新）。 */
    private String fingerprint(PlatePayload plate) {
        return props.getVehicle().getMqtt().getPlateType() + "|"
                + (plate.validFrom() == null ? "" : DATETIME.format(plate.validFrom())) + "|"
                + (plate.validTo() == null ? "" : DATETIME.format(plate.validTo()));
    }

    /** 直接发一段原始报文（协议探测/联调用）。 */
    public Map<String, Object> publishRaw(String deviceSn, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "报文不能为空");
        }
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload, "原始报文");
        return result("RAW", deviceSn, "", payload);
    }

    /* ---------------- 相机 → 平台 ---------------- */

    /** 上行：先留痕，再尽力解析车牌并落车辆通行记录。 */
    void onUpload(String topic, String payload) {
        String deviceSn = extractDeviceSn(topic);
        String command = extractText(payload, "command");
        if ("Rtd".equalsIgnoreCase(command)) {
            // 5 秒一条心跳，写日志表会把库撑爆（每天 3 万+ 行），只打 debug；
            // 但要顺带刷新「在线状态/内网 IP」，否则设备列表一直显示离线（限流 1 分钟一次）
            log.debug("车闸心跳 device={}", deviceSn);
            touchQuietly(deviceSn);
            return;
        }
        String plate = extractPlate(payload);
        if (plate == null && "Image".equalsIgnoreCase(command)) {
            log.debug("车闸图片上报（无车牌）device={}", deviceSn);
            return;
        }
        String message;
        if ("Conn".equalsIgnoreCase(command)) {
            Device device = registerQuietly(deviceSn, extractDeviceIp(payload));
            message = "设备上线（" + (device == null ? "未登记门点" : "通道=" + device.getChannel()
                    + "，门点=" + (device.getGateId() == null ? "待分配" : device.getGateId())) + "）";
            sideChannel.submit(() -> {
                configQuietly(deviceSn);
                syncQuietly(deviceSn, device == null ? null : device.getGateId());
            });
        } else if (plate == null) {
            message = describeWithoutPlate(command, payload);
            if (message.startsWith("下行回执：失败")) {
                // 相机拒绝了下行（如固件不支持该命令 / "White name list process busy."）：
                // 除了日志表与服务器日志，还要把该设备的对账状态清掉，下一轮定时任务整体重推，
                // 否则会「以为已下发」，现场名单少一条就打不开门。
                log.warn("车闸下行被相机拒绝 device={} {} raw={}", deviceSn, message, payload);
                sideChannel.submit(() -> {
                    try {
                        stateRepository.removeDevice(deviceSn);
                        log.warn("设备 {} 下行被拒，已清空对账状态，下一轮自动重推", deviceSn);
                    } catch (Exception e) {
                        log.warn("清理对账状态失败 device={}：{}", deviceSn, e.getMessage());
                    }
                });
            }
        } else {
            try {
                Device device = deviceSn == null ? null : deviceService.resolveOrRegister(deviceSn, null);
                Long gateId = device == null ? null : device.getGateId();
                int direction = device == null ? 1 : device.getDirection();
                VehicleAccessService.Decision decision = accessService.judge(plate, gateId, LocalDateTime.now());
                // 事件时间优先用相机上报的 triggT/imgT：离线补传时按“补传时刻”入库会把记录算到错误的日期
                LocalDateTime eventTime = eventTimeOf(payload);
                accessService.record(eventUuidOf(payload), deviceSn, gateId, plate, direction, eventTime,
                        confidenceOf(payload), decision, null, extractPhotoUrl(payload), payload);
                message = plate + " 判定=" + (decision.allowed() ? "放行" : "拒绝") + "：" + decision.reason();
            } catch (Exception e) {
                message = "落库失败：" + e.getMessage();
            }
        }
        try {
            logRepository.save("UP", deviceSn, topic, payload, message);
        } catch (Exception e) {
            log.warn("车闸上行日志写入失败 device={}：{}", deviceSn, e.getMessage());
        }
        log.debug("车闸上行 topic={} device={} plate={} msg={}", topic, deviceSn, plate, message);
    }

    /** 设备上线时下发配置（回执 + 离线补传）；失败只记日志，不影响上行处理。 */
    private void configQuietly(String deviceSn) {
        try {
            applyConfig(deviceSn);
            log.info("设备 {} 上线，已下发配置（回执 + 离线结果补传）", deviceSn);
        } catch (Exception e) {
            log.warn("设备 {} 配置下发失败：{}", deviceSn, e.getMessage());
        }
    }

    /** 相机上线时登记/刷新设备档案（「设备管理 → 车闸相机」才看得到它和心跳）。 */
    private Device registerQuietly(String deviceSn, String ip) {
        try {
            return deviceService.resolveOrRegister(deviceSn, ip);
        } catch (Exception e) {
            log.warn("设备登记失败 sn={}：{}", deviceSn, e.getMessage());
            return null;
        }
    }

    /**
     * 心跳顺带刷新在线状态；限流 1 分钟一次，避免每 5 秒一条把库写满。
     */
    private void touchQuietly(String deviceSn) {
        if (deviceSn == null || deviceSn.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastTouch.get(deviceSn);
        if (last != null && now - last < 60_000L) {
            return;
        }
        lastTouch.put(deviceSn, now);
        try {
            deviceService.touch(deviceSn, null);
        } catch (Exception e) {
            log.debug("刷新设备在线状态失败 sn={}：{}", deviceSn, e.getMessage());
        }
    }

    /** 相机 `Conn` 上行里带 `devIP~192.168.1.4`，拿它当设备档案的内网 IP。 */
    private static String extractDeviceIp(String payload) {
        if (payload == null) {
            return null;
        }
        Matcher matcher = DEVICE_IP.matcher(payload);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 相机上线后把车牌清单对齐一次（断网期间平台改过的也能追上）。 */
    private void syncQuietly(String deviceSn, Long gateId) {
        try {
            // 相机重连可能是「恢复出厂 / 清过白名单」之后：先把对账状态清掉，强制全量重推，
            // 否则指纹没变会被判成「已下发」，相机里其实是空的。
            stateRepository.removeDevice(deviceSn);
            log.info("设备 {} 上线对齐车牌（强制全量重推）：{}", deviceSn, syncDevicePlates(deviceSn, gateId));
        } catch (Exception e) {
            log.warn("设备 {} 上线对齐车牌失败：{}", deviceSn, e.getMessage());
        }
    }

    /** 相机上报的抓拍图外链（大图优先，其次小图）。 */
    private static String extractPhotoUrl(String payload) {
        String url = extractText(payload, "picN_full");
        if (url == null || url.isBlank()) {
            url = extractText(payload, "picMin_full");
        }
        return url == null || url.isBlank() ? null : url;
    }

    private static Integer extractInt(String payload, String field) {
        String text = extractText(payload, field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 相机给的置信度是 0-1000（`confid`），库里统一按 0-100 存（和行人侧的 score 口径一致）。
     */
    static Integer confidenceOf(String payload) {
        Integer raw = extractInt(payload, "confid");
        if (raw == null) {
            return null;
        }
        int value = Math.round(raw / 10.0f);
        return Math.max(0, Math.min(100, value));
    }

    /**
     * 事件时间：优先相机上报的 `triggT`（yyyy-MM-dd HH:mm:ss），其次 `imgT`（yyyyMMddHHmmss）。
     *
     * <p>相机开启离线补传后，恢复网络时会补发几小时前的记录；如果按"补传时刻"入库，
     * 跨零点/跨天的记录就会算错日期。相机时钟也不一定准，超前 5 分钟或早于 7 天的直接回落服务器时间。
     */
    static LocalDateTime eventTimeOf(String payload) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime parsed = parseDateTime(extractText(payload, "triggT"));
        if (parsed == null) {
            parsed = parseCompactDateTime(extractText(payload, "imgT"));
        }
        if (parsed == null) {
            return now;
        }
        if (parsed.isAfter(now.plusMinutes(5)) || parsed.isBefore(now.minusDays(7))) {
            return now;
        }
        return parsed;
    }

    /**
     * 事件幂等键：相机 QoS1 重传同一条结果时 `guid` 不变，用它做唯一键就能去重；没有 guid 才用随机值。
     */
    static String eventUuidOf(String payload) {
        String guid = extractText(payload, "guid");
        if (guid != null && !guid.isBlank()) {
            String value = "MQTT-" + guid.trim().toUpperCase();
            return value.length() <= 80 ? value : value.substring(0, 80);
        }
        return "MQTT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim().replace('T', ' ').replace('/', '-');
        try {
            return LocalDateTime.parse(value, DATETIME);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseCompactDateTime(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.length() != 14 || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, COMPACT_DATETIME);
        } catch (Exception e) {
            return null;
        }
    }

    /** 心跳/图片/回执这类非识别报文，日志里给个人话，不要一律写「未解析出车牌」。 */
    private static String describeWithoutPlate(String command, String payload) {
        String errCode = extractText(payload, "errCode");
        if (errCode != null) {
            return "0".equals(errCode.trim()) ? "下行回执：成功" : "下行回执：失败 errCode=" + errCode
                    + (extractText(payload, "errInfo") == null ? "" : " " + extractText(payload, "errInfo"));
        }
        if (command == null || command.isBlank()) {
            return "未解析出车牌（仅留痕）";
        }
        return switch (command) {
            case "Rtd" -> "心跳";
            case "Conn" -> "设备上线";
            case "Image" -> "图片上报（无车牌）";
            case "Result", "BackResult", "ResultVideo" -> payload != null && payload.contains("已加密")
                    ? "识别结果的车牌已加密（carNO 为密文），需在相机上关闭车牌加密或配置密钥"
                    : "识别结果但未解析出车牌";
            default -> "未解析出车牌（命令 " + command + "）";
        };
    }

    /* ---------------- 查询 ---------------- */

    public Map<String, Object> status() {
        Map<String, Object> view = new LinkedHashMap<>(gateway.status());
        AppProperties.Vehicle.Mqtt cfg = props.getVehicle().getMqtt();
        view.put("addTemplate", template(cfg.getAddTemplate(), DEFAULT_ADD));
        view.put("deleteTemplate", template(cfg.getDeleteTemplate(), DEFAULT_DELETE));
        view.put("importTemplate", template(cfg.getImportTemplate(), DEFAULT_IMPORT));
        view.put("plateType", cfg.getPlateType());
        return view;
    }

    public List<Map<String, Object>> recentLogs(int limit) {
        return logRepository.recent(Math.min(Math.max(limit, 1), 200));
    }

    public void clearLogs() {
        logRepository.clear();
    }

    public Map<String, Object> reconnect() {
        return gateway.reconnect();
    }

    /* ---------------- 内部 ---------------- */

    private Map<String, Object> result(String action, String deviceSn, String plate, String payload) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("action", action);
        view.put("deviceSn", deviceSn);
        view.put("plateNo", plate);
        view.put("topic", topicOf(deviceSn));
        view.put("payload", payload);
        view.put("connected", gateway.isConnected());
        return view;
    }

    private String topicOf(String deviceSn) {
        return props.getVehicle().getMqtt().getDownloadTopicPrefix() + deviceSn;
    }

    private static String template(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private static String fill(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    /** 补齐协议信封的公共字段：sn / requestId / time。 */
    private static Map<String, String> envelopeValues(String deviceSn, Map<String, String> values) {
        Map<String, String> all = new LinkedHashMap<>(values);
        all.put("sn", deviceSn);
        all.put("requestId", String.valueOf(System.currentTimeMillis()));
        all.put("time", DATETIME.format(LocalDateTime.now()));
        return all;
    }

    /** 一条 CarInfo（已按信封要求把引号转义，直接拼进 data 字符串里）。 */
    private static String carInfo(String plateNo, int type, LocalDateTime end) {
        return "{\\\"plate\\\":\\\"" + VehicleAccessService.normalize(plateNo) + "\\\",\\\"type\\\":" + type
                + ",\\\"time\\\":\\\"" + DATE.format(end) + "\\\"}";
    }

    /** 从 {@code upload/COPCM-E10-xxx} 里取设备 SN。 */
    private static String extractDeviceSn(String topic) {
        if (topic == null) {
            return null;
        }
        int idx = topic.lastIndexOf('/');
        return idx >= 0 && idx < topic.length() - 1 ? topic.substring(idx + 1) : topic;
    }

    /** 尽力从报文里取车牌：{@code data} 可能是对象，也可能是被转义的 JSON 字符串。 */
    private static String extractPlate(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            String plate = plateOf(node);
            if (plate != null) {
                return plate;
            }
            JsonNode data = node.path("data");
            if (data.isTextual()) {
                try {
                    plate = plateOf(MAPPER.readTree(data.asText()));
                } catch (Exception ignored) {
                    // data 不是 JSON，继续走正则兜底
                }
                if (plate != null) {
                    return plate;
                }
            }
        } catch (Exception ignored) {
            // 非 JSON，走正则
        }
        Matcher matcher = PLATE_PATTERN.matcher(payload.toUpperCase());
        return matcher.find() ? matcher.group() : null;
    }

    private static String plateOf(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return null;
        }
        if (node.isObject()) {
            for (String field : new String[]{"carNO", "plateNumber", "plateNo", "plate", "license", "carNo", "carNumber"}) {
                JsonNode value = node.get(field);
                if (value != null && !value.asText("").isBlank()) {
                    return VehicleAccessService.normalize(value.asText());
                }
            }
        }
        for (JsonNode child : node) {
            String plate = plateOf(child);
            if (plate != null) {
                return plate;
            }
        }
        return null;
    }

    private static String extractText(String payload, String field) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                return value.asText("");
            }
            JsonNode data = node.path("data");
            if (data.isTextual()) {
                JsonNode inner = MAPPER.readTree(data.asText()).get(field);
                if (inner != null && !inner.isNull()) {
                    return inner.asText("");
                }
            }
        } catch (Exception e) {
            // 非 JSON，忽略
        }
        return null;
    }
}
