package com.fangke.visitor.vehicle.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.pull.OpenApiSign;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.vehicle.model.VehicleEvent;
import com.fangke.visitor.vehicle.service.VehicleAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 车牌识别接入（C-68）：相机/车闸一体机推送车牌事件，平台判定后回 {@code openGate}。
 *
 * <p>兼容两种厂商能力：
 * <ul>
 *   <li>能接指令的（方案 B）：用返回的 {@code openGate} 决定是否开闸；</li>
 *   <li>只推不接的（方案 A/C）：忽略返回值，平台照常落通行记录。</li>
 * </ul>
 * 所以接口先按"推送 + 判定"实现，等厂商确认判定模式后不用改。
 */
@RestController
@RequestMapping("/openapi/v1")
public class VehicleOpenApiController {

    private static final Logger log = LoggerFactory.getLogger(VehicleOpenApiController.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper;
    private final VehicleAccessService accessService;
    private final DeviceRepository deviceRepository;
    private final AppProperties props;

    public VehicleOpenApiController(ObjectMapper mapper, VehicleAccessService accessService,
                                    DeviceRepository deviceRepository, AppProperties props) {
        this.mapper = mapper;
        this.accessService = accessService;
        this.deviceRepository = deviceRepository;
        this.props = props;
    }

    @PostMapping("/vehicleEvent")
    public Map<String, Object> vehicleEvent(@RequestBody(required = false) String rawText,
                                            HttpServletRequest request) {
        JsonNode body = parse(rawText);
        if (body == null) {
            return fail("请求体为空");
        }
        // 带 sign 就校验（我们的终端走签名）；相机不支持签名时允许裸推，靠内网 + 门点约束
        JsonNode signNode = body.get("sign");
        if (signNode != null && !signNode.asText("").isBlank()
                && props.getDevice().isPullVerifySign()
                && !OpenApiSign.verify(body, props.getDevice().getPullSignKey())) {
            log.warn("车辆事件签名错误 device={} 期望={} 实际={}", text(body, "deviceno", "deviceNo", "sn"),
                    OpenApiSign.expectedSign(body, props.getDevice().getPullSignKey()), signNode.asText());
            return fail("签名错误");
        }

        String deviceSn = text(body, "deviceno", "deviceNo", "sn", "deviceSn");
        JsonNode data = body.get("data") == null || body.get("data").isNull() ? body : body.get("data");
        if (data != null && data.isTextual()) {
            JsonNode inner = parse(data.asText());
            if (inner != null) {
                data = inner;
            }
        }

        String plateNo = text(data, "plateNo", "plate", "license", "plateNum", "carNo", "PlateNo");
        if (plateNo == null || plateNo.isBlank()) {
            return fail("缺少车牌号");
        }
        Long gateId = asLong(data, "gateId");
        if (gateId == null && deviceSn != null) {
            gateId = deviceRepository.findBySn(deviceSn).map(Device::getGateId).orElse(null);
        }
        int direction = asInt(data, "direction", "inout") == null ? 1 : asInt(data, "direction", "inout");
        LocalDateTime eventTime = parseTime(data);
        Integer confidence = asInt(data, "confidence", "score", "confidenceLevel");
        String photo = text(data, "picData", "image", "photoBase64", "picture");
        String eventUuid = text(data, "eventId", "id", "tableID", "uuid");
        if (eventUuid == null || eventUuid.isBlank()) {
            eventUuid = "V" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        }

        VehicleAccessService.Decision decision = accessService.judge(plateNo, gateId, eventTime);
        VehicleEvent saved = accessService.record(eventUuid, deviceSn, gateId, plateNo, direction,
                eventTime, confidence, decision, photo, rawText);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openGate", decision.allowed() ? 1 : 0);
        result.put("reason", decision.reason());
        result.put("plateNo", VehicleAccessService.normalize(plateNo));
        result.put("matchedType", decision.matchedType());
        result.put("matchedName", decision.matchedName());
        result.put("eventTime", DATETIME.format(eventTime));
        result.put("duplicate", saved == null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("msg", "成功");
        resp.put("data", result);
        log.info("车辆事件 ip={} plate={} gate={} 放行={} 原因={}",
                request.getRemoteAddr(), result.get("plateNo"), gateId,
                decision.allowed(), decision.reason());
        return resp;
    }

    /* ---------------- 解析工具 ---------------- */

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按候选字段名依次取值（兼容不同厂商命名）。 */
    private String text(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private Long asLong(JsonNode node, String... names) {
        String raw = text(node, names);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer asInt(JsonNode node, String... names) {
        String raw = text(node, names);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseTime(JsonNode data) {
        String raw = text(data, "time", "eventTime", "timestamp", "createTime");
        if (raw == null) {
            return LocalDateTime.now();
        }
        String value = raw.trim();
        if (value.matches("\\d{10}")) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(value)), ZoneId.systemDefault());
        }
        if (value.matches("\\d{13}")) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(value)), ZoneId.systemDefault());
        }
        try {
            return LocalDateTime.parse(value.replace('/', '-').replace('T', ' '),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 1);
        resp.put("msg", msg);
        return resp;
    }
}
