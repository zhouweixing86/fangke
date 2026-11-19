package com.fangke.visitor.admin.web;

import com.fangke.visitor.admin.repository.AdminConfigRepository;
import com.fangke.visitor.admin.service.ConfigAdminService;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置（docs/11 §5.9）：只读列表 + 白名单内更新。
 */
@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record SaveRequest(String key, String value, String remark) {
    }

    private final ConfigAdminService service;

    public AdminConfigController(ConfigAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        return ApiResponse.ok(service.list().stream().map(this::toView).toList());
    }

    @PutMapping
    @AuditLog(module = "CONFIG", action = "SAVE")
    public ApiResponse<Void> save(@RequestBody SaveRequest body, HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        service.save(body == null ? null : body.key(), body == null ? null : body.value(),
                body == null ? null : body.remark(), RoleGuard.currentUserId(request));
        return ApiResponse.ok();
    }

    private Map<String, Object> toView(AdminConfigRepository.Row row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", row.configKey());
        view.put("value", row.configValue() == null ? "" : row.configValue());
        view.put("encrypted", row.encrypted());
        view.put("remark", row.remark() == null ? "" : row.remark());
        view.put("updatedAt", row.updatedAt() == null ? "" : DATETIME.format(row.updatedAt()));
        return view;
    }
}
