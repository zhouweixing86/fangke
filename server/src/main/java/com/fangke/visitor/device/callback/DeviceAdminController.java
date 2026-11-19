package com.fangke.visitor.device.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.device.service.DeviceSelfCheckService;
import com.fangke.visitor.device.service.ReconcileService;
import com.fangke.visitor.gate.service.GateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 管理端设备接口（docs/11 §5.3，P1 只做只读 + 运维动作）。
 */
@RestController
@RequestMapping("/api/admin/device")
public class DeviceAdminController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DeviceRepository deviceRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;
    private final ReconcileService reconcileService;
    private final DeviceCapabilityService capabilityService;
    private final DeviceSelfCheckService selfCheckService;
    private final GateService gateService;

    public DeviceAdminController(DeviceRepository deviceRepository,
                                 DeviceAdapterFactory adapterFactory,
                                 DeviceGateway gateway,
                                 ReconcileService reconcileService,
                                 DeviceCapabilityService capabilityService,
                                 DeviceSelfCheckService selfCheckService,
                                 GateService gateService) {
        this.deviceRepository = deviceRepository;
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
        this.reconcileService = reconcileService;
        this.capabilityService = capabilityService;
        this.selfCheckService = selfCheckService;
        this.gateService = gateService;
    }

    /** 设备列表：可按门点过滤；不传 gateId 返回全部（多门点部署必须能看到所有门）。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(name = "gateId", required = false) Long gateId) {
        List<Device> devices = gateId == null
                ? deviceRepository.findAllActive()
                : deviceRepository.findActiveByGate(gateId);
        List<Map<String, Object>> view = devices.stream().map(this::toView).toList();
        return ApiResponse.ok(view);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(toView(load(id)));
    }

    /**
     * 补/改设备档案（docs/11 §5.3）：名称、型号、出入口方向、所属门点、备注。
     *
     * <p>轮询式终端首次心跳只带 SN 和 IP，会自动登记到「待分配」门点；
     * 现场装好后必须在这里分配到真实门点，否则该门点的名单不会下发到这台设备。
     */
    @PutMapping("/{id}")
    @AuditLog(module = "DEVICE", action = "UPDATE_META")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody DeviceUpdateRequest request) {
        load(id);
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求体不能为空");
        }
        Long gateId = request.gateId();
        if (gateId != null) {
            gateService.requireAssignable(gateId);
        }
        Integer direction = request.direction();
        if (direction != null && direction != 0 && direction != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "出入口方向只能是 0 出口 / 1 入口");
        }
        String deviceName = blankToNull(request.deviceName());
        if (deviceName != null && deviceName.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "设备名称最长 64 字");
        }
        String model = blankToNull(request.model());
        if (model != null && model.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "设备型号最长 64 字");
        }
        String remark = blankToNull(request.remark());
        if (remark != null && remark.length() > 255) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "备注最长 255 字");
        }
        deviceRepository.updateMeta(id, deviceName, model, direction, gateId, remark);
        return ApiResponse.ok(toView(load(id)));
    }

    /** 实时回读参数：以设备为准，不用本地缓存（docs/09 §1.1 开发要点 2）。 */
    @PostMapping("/{id}/params/read")
    public ApiResponse<JsonNode> readParams(@PathVariable Long id) {
        Device device = load(id);
        capabilityService.require(device, DeviceCapability.PARAM_READ, "读取参数");
        DeviceAdapter adapter = adapterFactory.resolve(device);
        JsonNode params = gateway.execute(device, "readParams", d -> adapter.readParams(d));
        return ApiResponse.ok(params);
    }

    /** 部分参数下发：适配层内部走「取全量 → 合并 → 回写」。 */
    @PostMapping("/{id}/params")
    @AuditLog(module = "DEVICE", action = "WRITE_PARAMS")
    public ApiResponse<Void> writeParams(@PathVariable Long id, @RequestBody Map<String, Object> partial) {
        if (partial == null || partial.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "参数不能为空");
        }
        Device device = load(id);
        capabilityService.require(device, DeviceCapability.PARAM_WRITE, "下发参数");
        DeviceAdapter adapter = adapterFactory.resolve(device);
        gateway.execute(device, "writeParams", d -> {
            adapter.writeParams(d, partial);
            return null;
        });
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/door/open")
    @AuditLog(module = "DEVICE", action = "OPEN_DOOR")
    public ApiResponse<Map<String, Object>> openDoor(@PathVariable Long id) {
        Device device = load(id);
        capabilityService.require(device, DeviceCapability.DOOR_OPEN, "远程开门");
        DeviceAdapter adapter = adapterFactory.resolve(device);
        gateway.execute(device, "openDoor", d -> {
            adapter.openDoor(d);
            return null;
        });
        return ApiResponse.ok(Map.of(
                "opened", true,
                "deviceSn", device.getDeviceSn(),
                "openedAt", DATETIME.format(LocalDateTime.now())));
    }

    @PostMapping("/{id}/message")
    public ApiResponse<Void> showMessage(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        if (message == null || message.isBlank() || message.length() > 14) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "屏幕提示需 1~14 个汉字");
        }
        Device device = load(id);
        capabilityService.require(device, DeviceCapability.SCREEN_MESSAGE, "屏幕提示");
        DeviceAdapter adapter = adapterFactory.resolve(device);
        gateway.execute(device, "showMessage", d -> {
            adapter.showMessage(d, message);
            return null;
        });
        return ApiResponse.ok();
    }

    /** 能力矩阵：换设备/交接时确认这台机器到底能做什么（docs/12 §2）。 */
    @GetMapping("/{id}/capabilities")
    public ApiResponse<List<Map<String, Object>>> capabilities(@PathVariable Long id) {
        return ApiResponse.ok(capabilityService.describe(load(id)));
    }

    /** 接入自检：逐项跑通道、版本、参数、名单，输出 OK/WARN/FAIL（docs/12 §3）。 */
    @PostMapping("/{id}/selfcheck")
    @AuditLog(module = "DEVICE", action = "SELF_CHECK")
    public ApiResponse<Map<String, Object>> selfCheck(@PathVariable Long id) {
        return ApiResponse.ok(selfCheckService.check(load(id)));
    }

    /** 手动触发对账（docs/11 §5.3），返回缺失/多余/已删除明细。 */
    @PostMapping("/{id}/resync")
    @AuditLog(module = "DEVICE", action = "RESYNC")
    public ApiResponse<ReconcileService.Result> resync(@PathVariable Long id) {
        load(id);
        return ApiResponse.ok(reconcileService.reconcile(id));
    }

    private Device load(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "设备不存在：" + id));
    }

    private Map<String, Object> toView(Device d) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", d.getId() == null ? 0L : d.getId());
        view.put("deviceSn", nullSafe(d.getDeviceSn()));
        view.put("mac", nullSafe(d.getMac()));
        view.put("deviceName", nullSafe(d.getDeviceName()));
        view.put("model", nullSafe(d.getModel()));
        view.put("firmware", nullSafe(d.getFirmware()));
        view.put("direction", d.getDirection() == null ? 0 : d.getDirection());
        view.put("directionText", Integer.valueOf(1).equals(d.getDirection()) ? "入口" : "出口");
        view.put("gateId", d.getGateId() == null ? 0L : d.getGateId());
        view.put("gateName", gateService.nameOf(d.getGateId()));
        view.put("channel", d.getChannel() == null ? "LAN" : d.getChannel().name());
        view.put("ip", nullSafe(d.getIp()));
        view.put("online", d.isOnline());
        view.put("lastHeartbeat", d.getLastHeartbeat() == null ? "" : DATETIME.format(d.getLastHeartbeat()));
        view.put("personCount", d.getPersonCount() == null ? 0 : d.getPersonCount());
        view.put("capacity", d.getCapacity() == null ? 0 : d.getCapacity());
        view.put("remark", nullSafe(d.getRemark()));
        return view;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 设备档案补录请求；字段为 null 表示不改该项。 */
    public record DeviceUpdateRequest(String deviceName, String model, Integer direction,
                                      Long gateId, String remark) {
    }
}
