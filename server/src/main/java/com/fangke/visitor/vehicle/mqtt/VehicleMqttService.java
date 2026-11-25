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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车闸车牌 MQTT 下发与识别结果接收（docs/22）。
 *
 * <p>平台 → 相机：往 {@code download/{SN}} 发白名单增/删报文；
 * 相机 → 平台：订阅 {@code upload/+}，把识别结果落到车辆通行记录（复用现有判定逻辑）。
 *
 * <p>报文格式按相机「标准协议」的命令风格（{@code CmdName + Data}），
 * 模板可在配置里改（{@code fangke.vehicle.mqtt.*-template}），现场抓到真实报文后无需改代码。
 */
@Service
public class VehicleMqttService {

    private static final Logger log = LoggerFactory.getLogger(VehicleMqttService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 宽松车牌识别：汉字 + 字母 + 5~6 位字母数字。 */
    private static final Pattern PLATE_PATTERN =
            Pattern.compile("[京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新][A-Z][A-Z0-9]{4,6}");

    private static final String DEFAULT_ADD = "{\"CmdName\":\"AddNameList\",\"Data\":{\"plateNumber\":\"${plate}\",\"beginTime\":\"${begin}\",\"endTime\":\"${end}\"}}";
    private static final String DEFAULT_DELETE = "{\"CmdName\":\"DelNameList\",\"Data\":{\"plateNumber\":\"${plate}\"}}";
    private static final String DEFAULT_IMPORT = "{\"CmdName\":\"ImportNameList\",\"Data\":{\"items\":[${items}]}}";

    private final AppProperties props;
    private final VehicleMqttGateway gateway;
    private final VehicleMqttLogRepository logRepository;
    private final VehicleAccessService accessService;
    private final VehicleDeviceService deviceService;

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

    /* ---------------- 平台 → 相机 ---------------- */

    /** 新增/更新一条车牌（有效期到日）。 */
    public Map<String, Object> addPlate(String deviceSn, String plateNo, LocalDateTime begin, LocalDateTime end) {
        String plate = VehicleAccessService.normalize(plateNo);
        if (plate.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "车牌号不能为空");
        }
        String template = template(props.getVehicle().getMqtt().getAddTemplate(), DEFAULT_ADD);
        String payload = fill(template, Map.of(
                "plate", plate,
                "begin", DATETIME.format(begin == null ? LocalDateTime.now() : begin),
                "end", DATETIME.format(end == null ? LocalDateTime.now().plusDays(1) : end),
                "sn", deviceSn));
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
        String payload = fill(template, Map.of("plate", plate, "sn", deviceSn));
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload, "删除车牌 " + plate);
        return result("DELETE", deviceSn, plate, payload);
    }

    /** 全量覆盖车牌（员工 + 当天已授权访客），用于对齐。 */
    public Map<String, Object> importPlates(String deviceSn, List<PlatePayload> plates) {
        List<String> items = new ArrayList<>();
        for (PlatePayload plate : plates) {
            items.add("{\"plateNumber\":\"" + plate.plateNo() + "\",\"beginTime\":\""
                    + DATETIME.format(plate.validFrom() == null ? LocalDateTime.now() : plate.validFrom())
                    + "\",\"endTime\":\""
                    + DATETIME.format(plate.validTo() == null ? LocalDateTime.now().plusYears(10) : plate.validTo())
                    + "\"}");
        }
        String template = template(props.getVehicle().getMqtt().getImportTemplate(), DEFAULT_IMPORT);
        String payload = fill(template, Map.of("items", String.join(",", items), "sn", deviceSn));
        gateway.publish(deviceSn, payload);
        logRepository.save("DOWN", deviceSn, topicOf(deviceSn), payload, "全量下发 " + plates.size() + " 条车牌");
        Map<String, Object> view = result("IMPORT", deviceSn, "", payload);
        view.put("count", plates.size());
        return view;
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
        String plate = extractPlate(payload);
        String message;
        if (plate == null) {
            message = "未解析出车牌（仅留痕）";
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
        log.info("车闸上行 topic={} device={} plate={} msg={}", topic, deviceSn, plate, message);
    }

    /* ---------------- 查询 ---------------- */

    public Map<String, Object> status() {
        Map<String, Object> view = new LinkedHashMap<>(gateway.status());
        AppProperties.Vehicle.Mqtt cfg = props.getVehicle().getMqtt();
        view.put("addTemplate", template(cfg.getAddTemplate(), DEFAULT_ADD));
        view.put("deleteTemplate", template(cfg.getDeleteTemplate(), DEFAULT_DELETE));
        view.put("importTemplate", template(cfg.getImportTemplate(), DEFAULT_IMPORT));
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

    /** 从 {@code upload/COPCM-E10-xxx} 里取设备 SN。 */
    private static String extractDeviceSn(String topic) {
        if (topic == null) {
            return null;
        }
        int idx = topic.lastIndexOf('/');
        return idx >= 0 && idx < topic.length() - 1 ? topic.substring(idx + 1) : topic;
    }

    /** 尽力从报文里取车牌：先找常见 JSON 字段，再正则兜底。 */
    private static String extractPlate(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode data = node.path("data");
            JsonNode target = data.isObject() ? data : node;
            for (String field : new String[]{"plateNumber", "plateNo", "plate", "license", "carNo", "PlateNo"}) {
                JsonNode value = target.get(field);
                if (value != null && !value.asText("").isBlank()) {
                    return VehicleAccessService.normalize(value.asText());
                }
            }
        } catch (Exception ignored) {
            // 非 JSON，走正则
        }
        Matcher matcher = PLATE_PATTERN.matcher(payload.toUpperCase());
        return matcher.find() ? matcher.group() : null;
    }


}
