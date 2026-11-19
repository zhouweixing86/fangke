package com.fangke.visitor.guard.web;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.guard.service.GuardService;
import com.fangke.visitor.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 保安工作台接口（docs/11 §4）。
 *
 * <p>保安<b>没有审批权</b>：本控制器不提供通过/驳回，只有查看、催办、开门、签离。
 */
@RestController
@RequestMapping("/api/guard")
public class GuardController {

    private final GuardService guardService;
    private final GateService gateService;

    public GuardController(GuardService guardService, GateService gateService) {
        this.guardService = guardService;
        this.gateService = gateService;
    }

    /**
     * 保安可操作的门点（多门点部署时人工开门要先选门）。
     *
     * <p>保安账号没有 ADMIN 角色，不能调 /api/admin/gate；这里只出 id 与名称，不暴露设备与配置。
     */
    @GetMapping("/gate")
    public ApiResponse<List<Map<String, Object>>> gates(HttpServletRequest request) {
        requireGuard(request);
        List<Map<String, Object>> list = gateService.listSelectable().stream()
                .map(g -> Map.<String, Object>of("id", g.id(), "gateName", g.gateName(),
                        "gateCode", g.gateCode()))
                .toList();
        return ApiResponse.ok(list);
    }

    @GetMapping("/dashboard")
    public ApiResponse<GuardService.DashboardView> dashboard(HttpServletRequest request) {
        requireGuard(request);
        return ApiResponse.ok(guardService.dashboard());
    }

    @GetMapping("/visit/onsite")
    public ApiResponse<PageResult<GuardService.OnsiteView>> onsite(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        requireGuard(request);
        return ApiResponse.ok(guardService.onsite(page, size));
    }

    /** 自助登记待审批列表（只读，保安不能审批）。 */
    @GetMapping("/appointment/pending")
    public ApiResponse<PageResult<GuardService.PendingView>> pending(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        requireGuard(request);
        return ApiResponse.ok(guardService.pending(page, size));
    }

    @PostMapping("/appointment/{appointmentNo}/remind")
    @AuditLog(module = "GUARD", action = "REMIND")
    public ApiResponse<Map<String, Object>> remind(@PathVariable String appointmentNo,
                                                    HttpServletRequest request) {
        requireGuard(request);
        int remaining = guardService.remind(appointmentNo, operatorId(request));
        return ApiResponse.ok(Map.of("remaining", remaining));
    }

    @PostMapping("/door/open")
    @AuditLog(module = "GUARD", action = "OPEN_DOOR")
    public ApiResponse<Map<String, Object>> openDoor(@RequestBody GuardService.OpenDoorRequest body,
                                                      HttpServletRequest request) {
        requireGuard(request);
        int opened = guardService.openDoor(body, operatorId(request));
        return ApiResponse.ok(Map.of("opened", opened > 0, "devices", opened));
    }

    @PostMapping("/visit/{appointmentNo}/checkout")
    @AuditLog(module = "GUARD", action = "MANUAL_CHECKOUT")
    public ApiResponse<Void> checkout(@PathVariable String appointmentNo, HttpServletRequest request) {
        requireGuard(request);
        guardService.manualCheckout(appointmentNo, operatorId(request));
        return ApiResponse.ok();
    }

    @SuppressWarnings("unchecked")
    private void requireGuard(HttpServletRequest request) {
        Object roles = request.getAttribute(AuthInterceptor.ATTR_ROLES);
        if (!(roles instanceof List<?> list)
                || list.stream().noneMatch(r -> "GUARD".equals(r) || "ADMIN".equals(r))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要保安或管理员角色");
        }
    }

    private Long operatorId(HttpServletRequest request) {
        Object uid = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return uid instanceof Long l ? l : null;
    }
}
