package com.fangke.visitor.device.remote;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.service.DeviceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备远程管理（docs/17 §11）。
 *
 * <p>与 {@code /api/admin/device/{id}/door/open} 的区别：
 * <ul>
 *   <li>那个走「指令队列 + 设备心跳」，云端也能用，但要等一个心跳周期（当前 5 秒）</li>
 *   <li>这里的 {@code /remote/open} 直连设备 9090，<b>立即</b>开闸，但要求平台与设备同网</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/device/{id}/remote")
public class DeviceRemoteController {

    public record TipRequest(String msg, Integer color, Integer playId) {
    }

    public record GateHoldRequest(Boolean enabled, Integer minutes) {
    }

    private final DeviceRepository deviceRepository;
    private final DeviceRemoteService remoteService;

    public DeviceRemoteController(DeviceRepository deviceRepository, DeviceRemoteService remoteService) {
        this.deviceRepository = deviceRepository;
        this.remoteService = remoteService;
    }

    /**
     * 远程能力总览：设备 IP、本地后台地址、远程是否可达。
     *
     * <p>可达性用一次版本查询探测，失败不报错——云端部署时本来就连不上，前端据此提示改走本地管理。
     */
    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info(@PathVariable Long id) {
        Device device = load(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deviceId", device.getId());
        view.put("deviceSn", device.getDeviceSn());
        view.put("ip", device.getIp());
        view.put("localAdminUrl", remoteService.localAdminUrl(device));
        try {
            view.put("version", remoteService.version(device));
            view.put("reachable", true);
        } catch (BusinessException e) {
            view.put("reachable", false);
            view.put("unreachableReason", e.getMessage());
        }
        return ApiResponse.ok(view);
    }

    @GetMapping("/version")
    public ApiResponse<Map<String, Object>> version(@PathVariable Long id) {
        return ApiResponse.ok(remoteService.version(load(id)));
    }

    @GetMapping("/parameters")
    public ApiResponse<Map<String, Object>> parameters(@PathVariable Long id) {
        return ApiResponse.ok(remoteService.parameters(load(id)));
    }

    @GetMapping("/time")
    public ApiResponse<Map<String, Object>> time(@PathVariable Long id) {
        return ApiResponse.ok(remoteService.time(load(id)));
    }

    @PostMapping("/open")
    @AuditLog(module = "DEVICE", action = "REMOTE_OPEN")
    public ApiResponse<Map<String, Object>> open(@PathVariable Long id) {
        return ApiResponse.ok(remoteService.openGate(load(id)));
    }

    @PostMapping("/sync-time")
    @AuditLog(module = "DEVICE", action = "REMOTE_SYNC_TIME")
    public ApiResponse<Map<String, Object>> syncTime(@PathVariable Long id) {
        return ApiResponse.ok(remoteService.syncTime(load(id)));
    }

    @PostMapping("/tip")
    @AuditLog(module = "DEVICE", action = "REMOTE_TIP")
    public ApiResponse<Map<String, Object>> tip(@PathVariable Long id, @RequestBody TipRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求体不能为空");
        }
        return ApiResponse.ok(remoteService.showTip(load(id), request.msg(), request.color(), request.playId()));
    }

    @PostMapping("/snapshot")
    @AuditLog(module = "DEVICE", action = "REMOTE_SNAPSHOT")
    public ApiResponse<Map<String, Object>> snapshot(@PathVariable Long id) {
        return ApiResponse.ok(remoteService.snapshot(load(id)));
    }

    @PostMapping("/gate-hold")
    @AuditLog(module = "DEVICE", action = "REMOTE_GATE_HOLD")
    public ApiResponse<Map<String, Object>> gateHold(@PathVariable Long id,
                                                     @RequestBody(required = false) GateHoldRequest request) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        Integer minutes = request == null ? null : request.minutes();
        return ApiResponse.ok(remoteService.gateHold(load(id), enabled, minutes));
    }

    @PostMapping("/reboot")
    @AuditLog(module = "DEVICE", action = "REMOTE_REBOOT")
    public ApiResponse<Map<String, Object>> reboot(@PathVariable Long id) {
        return ApiResponse.ok(remoteService.reboot(load(id)));
    }

    @PostMapping("/restore")
    @AuditLog(module = "DEVICE", action = "REMOTE_RESTORE")
    public ApiResponse<Map<String, Object>> restore(@PathVariable Long id) {
        return ApiResponse.ok(remoteService.restoreDefaults(load(id)));
    }

    private Device load(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "设备不存在：" + id));
    }
}
