package com.fangke.visitor.approval.web;

import com.fangke.visitor.approval.service.ApprovalService;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门卫复核接口（C-65）：被访人确认后，由保安做二级复核，通过才下发门禁授权。
 *
 * <p>为什么二级不用员工账号：保安是后台账号（sys_user + GUARD 角色），
 * 且轮班值守，所以这里是**角色任务**——该角色的任一账号都能看到并处理同一批待复核。
 */
@RestController
@RequestMapping("/api/guard/approval")
public class GuardApprovalController {

    private final ApprovalService approvalService;

    public GuardApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/todo")
    public ApiResponse<PageResult<ApprovalService.TodoView>> todo(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        RoleGuard.requireGuardOrAdmin(request);
        return ApiResponse.ok(approvalService.guardTodoList(RoleGuard.roles(request), page, size));
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> count(HttpServletRequest request) {
        RoleGuard.requireGuardOrAdmin(request);
        return ApiResponse.ok(Map.of("pending", approvalService.guardTodoCount(RoleGuard.roles(request))));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<ApprovalService.DetailView> detail(@PathVariable Long taskId,
                                                          HttpServletRequest request) {
        RoleGuard.requireGuardOrAdmin(request);
        return ApiResponse.ok(approvalService.guardDetail(taskId, RoleGuard.roles(request)));
    }

    /** 复核通过：可选调整开放门点（默认沿用被访人确认的集合）。 */
    @PostMapping("/{taskId}/pass")
    @AuditLog(module = "APPROVAL", action = "GUARD_PASS")
    public ApiResponse<ApprovalService.PassResult> pass(@PathVariable Long taskId,
                                                         @RequestBody(required = false) Map<String, Object> body,
                                                         HttpServletRequest request) {
        RoleGuard.requireGuardOrAdmin(request);
        String comment = body == null ? null : asString(body.get("comment"));
        List<Long> approvedGateIds = body == null ? null : asLongList(body.get("approvedGateIds"));
        Boolean qrApproved = body == null ? null : asBoolean(body.get("qrApproved"));
        return ApiResponse.ok(approvalService.guardPass(taskId, RoleGuard.currentUserId(request),
                RoleGuard.roles(request), comment, approvedGateIds, qrApproved));
    }

    /** 复核驳回（拒绝放行）：必须填原因，预约单直接驳回。 */
    @PostMapping("/{taskId}/reject")
    @AuditLog(module = "APPROVAL", action = "GUARD_REJECT")
    public ApiResponse<Void> reject(@PathVariable Long taskId,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        RoleGuard.requireGuardOrAdmin(request);
        approvalService.guardReject(taskId, RoleGuard.currentUserId(request),
                RoleGuard.roles(request), body == null ? null : asString(body.get("comment")));
        return ApiResponse.ok();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 「同意二维码开门」标志：{@code null} 表示不改。 */
    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "qrApproved 必须是布尔值");
    }

    private List<Long> asLongList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "approvedGateIds 必须是数组");
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            if (item instanceof Number n) {
                ids.add(n.longValue());
            } else {
                try {
                    ids.add(Long.parseLong(String.valueOf(item)));
                } catch (NumberFormatException e) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "门点 ID 必须是数字：" + item);
                }
            }
        }
        return ids;
    }
}
