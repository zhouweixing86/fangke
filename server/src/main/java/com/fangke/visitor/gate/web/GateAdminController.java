package com.fangke.visitor.gate.web;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.service.GateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端门点接口（docs/11 §5.2）。
 *
 * <p>{@code /api/admin/**} 的角色校验由 AuthInterceptor 集中处理，这里不再重复。
 */
@RestController
@RequestMapping("/api/admin/gate")
public class GateAdminController {

    private final GateService gateService;
    private final DeviceRepository deviceRepository;

    public GateAdminController(GateService gateService, DeviceRepository deviceRepository) {
        this.gateService = gateService;
        this.deviceRepository = deviceRepository;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(gateService.listAll().stream().map(this::toView).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        Gate gate = gateService.find(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "门点不存在：" + id));
        return ApiResponse.ok(toView(gate));
    }

    @PostMapping
    @AuditLog(module = "GATE", action = "CREATE")
    public ApiResponse<Map<String, Object>> create(@RequestBody GateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求体不能为空");
        }
        long id = gateService.create(request.gateCode(), request.gateName(),
                request.gateType(), request.sortNo(), request.remark());
        return ApiResponse.ok(toView(gateService.find(id).orElseThrow()));
    }

    @PutMapping("/{id}")
    @AuditLog(module = "GATE", action = "UPDATE")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody GateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求体不能为空");
        }
        gateService.update(id, request.gateName(), request.gateType(), request.sortNo(), request.remark());
        return ApiResponse.ok(toView(gateService.find(id).orElseThrow()));
    }

    /** 停用前必须先把设备挪到别的门点，否则那台设备会变成「无门可投递」。 */
    @PutMapping("/{id}/status")
    @AuditLog(module = "GATE", action = "STATUS")
    public ApiResponse<Map<String, Object>> changeStatus(@PathVariable Long id, @RequestBody GateRequest request) {
        int status = request == null || request.status() == null ? Gate.STATUS_ENABLED : request.status();
        if (status == Gate.STATUS_DISABLED) {
            int devices = deviceRepository.countByGate(id);
            if (devices > 0) {
                throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                        "该门点下还有 " + devices + " 台设备，请先移出设备再停用");
            }
        }
        gateService.setStatus(id, status);
        return ApiResponse.ok(toView(gateService.find(id).orElseThrow()));
    }

    private Map<String, Object> toView(Gate gate) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", gate.id() == null ? 0L : gate.id());
        view.put("gateCode", gate.gateCode());
        view.put("gateName", gate.gateName());
        view.put("gateType", gate.gateType());
        view.put("gateTypeText", gate.typeText());
        view.put("status", gate.status());
        view.put("statusText", gate.statusText());
        view.put("sortNo", gate.sortNo());
        view.put("remark", gate.remark() == null ? "" : gate.remark());
        view.put("unassigned", gate.unassigned());
        view.put("deviceCount", deviceRepository.countByGate(gate.id()));
        return view;
    }

    /**
     * @param status 仅状态接口使用；创建忽略（新门点默认启用），编辑忽略（改状态走 /status）
     */
    public record GateRequest(String gateCode, String gateName, Integer gateType,
                              Integer sortNo, String remark, Integer status) {
    }
}
