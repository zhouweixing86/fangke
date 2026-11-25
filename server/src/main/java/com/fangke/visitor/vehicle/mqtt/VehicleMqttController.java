package com.fangke.visitor.vehicle.mqtt;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.PlatePayload;
import com.fangke.visitor.vehicle.service.VehicleDeviceService;
import com.fangke.visitor.vehicle.service.VehiclePlateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车闸 MQTT 通道管理（docs/22）。
 *
 * <p>现场没有电脑、相机也没公网 IP 时，这是唯一可行的「平台下发车牌 → 相机识别开门」通道：
 * 相机作为 MQTT 客户端连我们云上的 broker，我们往 {@code download/{SN}} 发白名单命令。
 */
@RestController
@RequestMapping("/api/admin/vehicle/mqtt")
public class VehicleMqttController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record PlateRequest(String deviceSn, String plateNo, String beginTime, String endTime) {
    }

    public record RawRequest(String deviceSn, String payload) {
    }

    /** 人工开闸请求：车牌可选，带上就会记进该人的通行记录。 */
    public record OpenRequest(String deviceSn, String plateNo) {
    }

    private final VehicleMqttService service;
    private final VehicleDeviceService deviceService;
    private final VehiclePlateService plateService;

    public VehicleMqttController(VehicleMqttService service, VehicleDeviceService deviceService,
                                 VehiclePlateService plateService) {
        this.service = service;
        this.deviceService = deviceService;
        this.plateService = plateService;
    }

    /** 通道状态：连接、broker、上下行主题与当前报文模板。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(service.status());
    }

    /** 重新连接 broker（改了配置或断线时用）。 */
    @PostMapping("/reconnect")
    public ApiResponse<Map<String, Object>> reconnect() {
        return ApiResponse.ok(service.reconnect());
    }

    /** 最近上下行报文（新→旧）。 */
    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> logs(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.recentLogs(limit));
    }

    @DeleteMapping("/logs")
    @AuditLog(module = "VEHICLE", action = "MQTT_LOG_CLEAR")
    public ApiResponse<Map<String, Object>> clearLogs() {
        service.clearLogs();
        return ApiResponse.ok(Map.of("cleared", true));
    }

    /** 下发（新增/更新）一条车牌到相机。 */
    @PostMapping("/plate")
    @AuditLog(module = "VEHICLE", action = "MQTT_PLATE_ADD")
    public ApiResponse<Map<String, Object>> addPlate(@RequestBody PlateRequest request) {
        requirePlate(request);
        return ApiResponse.ok(service.addPlate(request.deviceSn().trim(), request.plateNo(),
                parseTime(request.beginTime(), LocalDateTime.now()),
                parseTime(request.endTime(), LocalDateTime.now().plusDays(1))));
    }

    /** 从相机删除一条车牌。 */
    @DeleteMapping("/plate")
    @AuditLog(module = "VEHICLE", action = "MQTT_PLATE_DELETE")
    public ApiResponse<Map<String, Object>> deletePlate(@RequestParam String deviceSn,
                                                        @RequestParam String plateNo) {
        if (deviceSn == null || deviceSn.isBlank() || plateNo == null || plateNo.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "deviceSn 与 plateNo 不能为空");
        }
        return ApiResponse.ok(service.deletePlate(deviceSn.trim(), plateNo));
    }

    /** 使能下行回执（相机回复 errCode，便于确认下发是否被接受）。 */
    @PostMapping("/enable-reply")
    @AuditLog(module = "VEHICLE", action = "MQTT_ENABLE_REPLY")
    public ApiResponse<Map<String, Object>> enableReply(@RequestParam String deviceSn) {
        if (deviceSn == null || deviceSn.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "deviceSn 不能为空");
        }
        return ApiResponse.ok(service.enableReply(deviceSn.trim()));
    }

    /** 下发协议配置开关（回执 + 离线结果补传）。 */
    @PostMapping("/config")
    @AuditLog(module = "VEHICLE", action = "MQTT_CONFIG")
    public ApiResponse<Map<String, Object>> config(@RequestParam String deviceSn) {
        if (deviceSn == null || deviceSn.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "deviceSn 不能为空");
        }
        return ApiResponse.ok(service.applyConfig(deviceSn.trim()));
    }

    /** 人工远程开闸（协议 Open）：可带车牌，带车牌时会记进该人的通行记录。 */
    @PostMapping("/open")
    @AuditLog(module = "VEHICLE", action = "MQTT_OPEN_GATE")
    public ApiResponse<Map<String, Object>> openGate(@RequestBody OpenRequest request,
                                                     jakarta.servlet.http.HttpServletRequest http) {
        if (request == null || request.deviceSn() == null || request.deviceSn().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "deviceSn 不能为空");
        }
        String operator = (String) http.getAttribute(com.fangke.visitor.security.AuthInterceptor.ATTR_USER_NAME);
        return ApiResponse.ok(service.openGate(request.deviceSn().trim(), request.plateNo(), operator));
    }

    /** 立即对齐某台相机的车牌（不等定时任务）。 */
    @PostMapping("/sync")
    @AuditLog(module = "VEHICLE", action = "MQTT_PLATE_SYNC")
    public ApiResponse<Map<String, Object>> sync(@RequestParam String deviceSn) {
        if (deviceSn == null || deviceSn.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "deviceSn 不能为空");
        }
        String sn = deviceSn.trim();
        Long gateId = deviceService.list(null).stream()
                .filter(d -> sn.equals(d.getDeviceSn()))
                .map(Device::getGateId)
                .findFirst().orElse(null);
        return ApiResponse.ok(service.syncDevicePlates(sn, gateId));
    }

    /** 直接发一段原始报文（协议探测/联调）。 */
    @PostMapping("/raw")
    @AuditLog(module = "VEHICLE", action = "MQTT_RAW")
    public ApiResponse<Map<String, Object>> raw(@RequestBody RawRequest request) {
        if (request == null || request.deviceSn() == null || request.deviceSn().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "deviceSn 不能为空");
        }
        return ApiResponse.ok(service.publishRaw(request.deviceSn().trim(), request.payload()));
    }

    /**
     * 把平台当前的「员工 + 当天已授权访客」车牌全量推到所有车闸相机。
     *
     * <p>等价于「对齐一次」，适合首次接入或怀疑相机名单与平台不一致时用。
     */
    @PostMapping("/import")
    @AuditLog(module = "VEHICLE", action = "MQTT_PLATE_IMPORT")
    public ApiResponse<Map<String, Object>> importAll(@RequestParam(required = false) Long gateId) {
        List<PlatePayload> plates = plateService.snapshot(gateId);
        List<Device> devices = deviceService.list(gateId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Device device : devices) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceSn", device.getDeviceSn());
            try {
                row.put("result", service.importPlates(device.getDeviceSn(), plates));
            } catch (Exception e) {
                row.put("error", e.getMessage());
            }
            results.add(row);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("plateTotal", plates.size());
        view.put("devices", results);
        return ApiResponse.ok(view);
    }

    private void requirePlate(PlateRequest request) {
        if (request == null || request.deviceSn() == null || request.deviceSn().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "deviceSn 不能为空");
        }
        if (request.plateNo() == null || request.plateNo().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "plateNo 不能为空");
        }
    }

    private LocalDateTime parseTime(String text, LocalDateTime fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(text.trim().replace(' ', 'T'));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "时间格式应为 yyyy-MM-dd HH:mm:ss：" + text);
        }
    }

    static String format(LocalDateTime time) {
        return DATETIME.format(time);
    }
}
