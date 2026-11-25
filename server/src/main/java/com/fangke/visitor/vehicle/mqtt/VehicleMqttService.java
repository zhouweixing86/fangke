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
import java.util.List;
import java.util.Map;
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
    /** V1 固定车的到期日只有日期。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 宽松车牌识别：汉字 + 字母 + 5~6 位字母数字。 */
    private static final Pattern PLATE_PATTERN =
            Pattern.compile("[京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新][A-Z][A-Z0-9]{4,6}");

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

    private final AppProperties props;
    private final VehicleMqttGateway gateway;
    private final VehicleMqttLogRepository logRepository;
    private final VehicleAccessService accessService;
    private final VehicleDeviceService deviceService;

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
                              VehicleDeviceService deviceService) {
        this.props = props;
        this.gateway = gateway;
        this.logRepository = logRepository;
        this.accessService = accessService;
        this.deviceService = deviceService;
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
            // 5 秒一条心跳，写日志表会把库撑爆（每天 3 万+ 行），只打 debug
            log.debug("车闸心跳 device={}", deviceSn);
            return;
        }
        String plate = extractPlate(payload);
        if (plate == null && "Image".equalsIgnoreCase(command)) {
            log.debug("车闸图片上报（无车牌）device={}", deviceSn);
            return;
        }
        String message;
        if ("Conn".equalsIgnoreCase(command)) {
            message = "设备上线";
            sideChannel.submit(() -> enableReplyQuietly(deviceSn));
        } else if (plate == null) {
            message = describeWithoutPlate(command, payload);
        } else {
            try {
                Device device = deviceSn == null ? null : deviceService.resolveOrRegister(deviceSn, null);
                Long gateId = device == null ? null : device.getGateId();
                VehicleAccessService.Decision decision = accessService.judge(plate, gateId, LocalDateTime.now());
                accessService.record("MQTT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                        deviceSn, gateId, plate, 1, LocalDateTime.now(), null, decision, null, payload);
                message = plate + " 判定=" + (decision.allowed() ? "放行" : "拒绝") + "：" + decision.reason();
            } catch (Exception e) {
                message = "落库失败：" + e.getMessage();
            }
        }
        logRepository.save("UP", deviceSn, topic, payload, message);
        log.debug("车闸上行 topic={} device={} plate={} msg={}", topic, deviceSn, plate, message);
    }

    /** 设备上线时自动使能下行回执；失败只记日志，不影响上行处理。 */
    private void enableReplyQuietly(String deviceSn) {
        try {
            enableReply(deviceSn);
            log.info("设备 {} 上线，已下行使能下行回执", deviceSn);
        } catch (Exception e) {
            log.warn("设备 {} 使能回执下发失败：{}", deviceSn, e.getMessage());
        }
    }

    /** 心跳/图片/回执这类非识别报文，日志里给个人话，不要一律写「未解析出车牌」。 */
    private static String describeWithoutPlate(String command, String payload) {
        String errCode = extractText(payload, "errCode");
        if (errCode != null) {
            return "0".equals(errCode.trim()) ? "下行回执：成功" : "下行回执：失败 errCode=" + errCode;
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
