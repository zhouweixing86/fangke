package com.fangke.visitor.admin.web;

import com.fangke.visitor.admin.repository.AdminUserRepository;
import com.fangke.visitor.admin.service.UserAdminService;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号与角色管理（docs/11 §5.9）。除「改自己密码」外都要求 ADMIN 角色。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record CreateRequest(String username, String realName, Integer userType,
                                String password, List<String> roles) {
    }

    public record UpdateRequest(String realName, Integer userType, List<String> roles) {
    }

    public record PasswordRequest(String newPassword) {
    }

    public record OwnPasswordRequest(String oldPassword, String newPassword) {
    }

    public record StatusRequest(Integer status) {
    }

    private final UserAdminService service;

    public AdminUserController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping("/role")
    public ApiResponse<List<AdminUserRepository.RoleRow>> roles(HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        return ApiResponse.ok(service.roles());
    }

    @GetMapping("/user")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        UserAdminService.Page result = service.search(keyword, status, safePage, safeSize);
        List<Map<String, Object>> list = result.list().stream().map(this::toView).toList();
        return ApiResponse.ok(PageResult.of(result.total(), safePage, safeSize, list));
    }

    @PostMapping("/user")
    @AuditLog(module = "ACCOUNT", action = "CREATE")
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        Long id = service.create(body.username(), body.realName(), body.userType(), body.password(), body.roles());
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/user/{id}")
    @AuditLog(module = "ACCOUNT", action = "UPDATE")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody UpdateRequest body,
                                    HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        service.update(id, body.realName(), body.userType(), body.roles(), RoleGuard.currentUserId(request));
        return ApiResponse.ok();
    }

    /** 管理员重置他人密码。 */
    @PutMapping("/user/{id}/password")
    @AuditLog(module = "ACCOUNT", action = "RESET_PASSWORD")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @RequestBody PasswordRequest body,
                                           HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        service.resetPassword(id, body == null ? null : body.newPassword());
        return ApiResponse.ok();
    }

    @PutMapping("/user/{id}/status")
    @AuditLog(module = "ACCOUNT", action = "UPDATE_STATUS")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody StatusRequest body,
                                          HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        service.changeStatus(id, body == null ? null : body.status(), RoleGuard.currentUserId(request));
        return ApiResponse.ok();
    }

    /** 任何已登录账号都能改自己的密码（保安也需要）。 */
    @PutMapping("/user/me/password")
    @AuditLog(module = "ACCOUNT", action = "CHANGE_OWN_PASSWORD")
    public ApiResponse<Void> changeOwnPassword(@RequestBody OwnPasswordRequest body,
                                               HttpServletRequest request) {
        service.changeOwnPassword(RoleGuard.currentUserId(request),
                body == null ? null : body.oldPassword(), body == null ? null : body.newPassword());
        return ApiResponse.ok();
    }

    private Map<String, Object> toView(AdminUserRepository.Row row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("username", row.username());
        view.put("realName", row.realName());
        view.put("userType", row.userType());
        view.put("userTypeText", switch (row.userType()) {
            case 2 -> "访客管理员";
            case 3 -> "保安";
            default -> "管理员";
        });
        view.put("status", row.status());
        view.put("statusText", row.status() == 1 ? "启用" : "停用");
        view.put("roles", row.roles());
        view.put("lastLoginAt", row.lastLoginAt() == null ? "" : DATETIME.format(row.lastLoginAt()));
        view.put("createdAt", row.createdAt() == null ? "" : DATETIME.format(row.createdAt()));
        return view;
    }
}
