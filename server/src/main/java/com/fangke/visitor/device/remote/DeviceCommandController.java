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

import java.util.Map;

/**
 * 远程管理的「心跳下发」接口（docs/17 §11.2）。
 *
 * <p>与 {@code /remote/*}（平台直连设备 9090）互补：这里的指令全部走 {@code device_cmd} 队列，
 * 由设备心跳来取，<b>云端部署也能用</b>；代价是延迟一个心跳周期。
 *
 * <p>协议支持心跳下发的只有：开门、校时、参数下发（含安全开关/二维码接线）。
 * 抓拍、弹框、重启、恢复出厂、读参数设备不提供上行通道，仍走 {@code /remote/*} 直连。
 */
@RestController
@RequestMapping("/api/admin/device/{id}/cmd")
public class DeviceCommandController {

    private final DeviceRepository deviceRepository;
    private final DeviceCommandService commandService;

    public DeviceCommandController(DeviceRepository deviceRepository, DeviceCommandService commandService) {
        this.deviceRepository = deviceRepository;
        this.commandService = commandService;
    }

    /** 心跳下发总览：在线状态、待执行指令数、平台期望参数、最近 10 条指令结果。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> status(@PathVariable Long id) {
        return ApiResponse.ok(commandService.status(load(id)));
    }

    /** 远程开门：下一个心跳生效（秒级，云端也能用）。 */
    @PostMapping("/open")
    @AuditLog(module = "DEVICE", action = "CMD_OPEN_DOOR")
    public ApiResponse<Map<String, Object>> open(@PathVariable Long id) {
        return ApiResponse.ok(commandService.openDoor(load(id)));
    }

    /** 校时：下一个心跳生效。 */
    @PostMapping("/sync-time")
    @AuditLog(module = "DEVICE", action = "CMD_SYNC_TIME")
    public ApiResponse<Map<String, Object>> syncTime(@PathVariable Long id) {
        return ApiResponse.ok(commandService.syncTime(load(id)));
    }

    /** 触发一次参数下发（内容由设备来拉时生成）。 */
    @PostMapping("/sync-params")
    @AuditLog(module = "DEVICE", action = "CMD_SYNC_PARAMS")
    public ApiResponse<Map<String, Object>> syncParams(@PathVariable Long id) {
        return ApiResponse.ok(commandService.syncParams(load(id)));
    }

    /** 二维码核验接线：参数下发里带上 thirdQrUrl + thirdOpenDoorLocal=0。 */
    @PostMapping("/qr-config")
    @AuditLog(module = "DEVICE", action = "CMD_QR_CONFIG")
    public ApiResponse<Map<String, Object>> applyQrConfig(@PathVariable Long id) {
        return ApiResponse.ok(commandService.applyQrConfig(load(id)));
    }

    /**
     * 改安全开关（白名单键，见 {@link com.fangke.visitor.device.service.DeviceParamOverrides}）。
     *
     * <p>期望值落库，设备下次心跳拉参数时生效；恢复出厂后重新拉参数依然生效。
     */
    @PostMapping("/params")
    @AuditLog(module = "DEVICE", action = "CMD_SET_PARAMS")
    public ApiResponse<Map<String, Object>> setParams(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求体不能为空");
        }
        return ApiResponse.ok(commandService.setParams(load(id), body));
    }

    private Device load(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "设备不存在：" + id));
    }
}
