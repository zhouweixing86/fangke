package com.fangke.visitor.admin.web;

import com.fangke.visitor.admin.service.BlacklistAdminService;
import com.fangke.visitor.appointment.repository.BlacklistRepository;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 黑名单管理（docs/11 §5.7）。列表只出脱敏值，明文不落日志、不出接口。
 */
@RestController
@RequestMapping("/api/admin/blacklist")
public class AdminBlacklistController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record CreateRequest(
            Integer targetType,
            String target,
            String reason,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validTo) {
    }

    public record StatusRequest(Integer status) {
    }

    private final BlacklistAdminService service;

    public AdminBlacklistController(BlacklistAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        BlacklistAdminService.Page result = service.search(keyword, status, safePage, safeSize);
        List<Map<String, Object>> list = result.list().stream().map(this::toView).toList();
        return ApiResponse.ok(PageResult.of(result.total(), safePage, safeSize, list));
    }

    @PostMapping
    @AuditLog(module = "BLACKLIST", action = "CREATE")
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest request,
                                                   HttpServletRequest httpRequest) {
        Long operatorId = operatorId(httpRequest);
        Long id = service.create(request.targetType(), request.target(), request.reason(),
                request.validFrom(), request.validTo(), operatorId);
        return ApiResponse.ok(Map.of("id", id));
    }

    /** 失效/恢复：不物理删除，保留拉黑痕迹（BR-D-010 审计要求）。 */
    @PutMapping("/{id}/status")
    @AuditLog(module = "BLACKLIST", action = "UPDATE_STATUS")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        service.updateStatus(id, request == null ? null : request.status());
        return ApiResponse.ok();
    }

    /**
     * 立即生效（BR-B-003）：撤销该访客所有在途预约的授权、删设备名单、作废通行码。
     */
    @PostMapping("/{id}/enforce")
    @AuditLog(module = "BLACKLIST", action = "ENFORCE")
    public ApiResponse<BlacklistAdminService.EnforceResult> enforce(@PathVariable Long id,
                                                                   HttpServletRequest request) {
        return ApiResponse.ok(service.enforce(id, operatorId(request)));
    }

    private Long operatorId(HttpServletRequest request) {
        Object uid = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return uid instanceof Long l ? l : null;
    }

    private Map<String, Object> toView(com.fangke.visitor.admin.repository.AdminBlacklistRepository.Row row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("targetType", row.targetType());
        view.put("targetTypeText", row.targetType() == BlacklistRepository.TARGET_ID_CARD ? "身份证号" : "手机号");
        view.put("targetMask", row.targetMask());
        view.put("reason", row.reason());
        view.put("validFrom", row.validFrom() == null ? "" : DATETIME.format(row.validFrom()));
        view.put("validTo", row.validTo() == null ? "" : DATETIME.format(row.validTo()));
        view.put("status", row.status());
        view.put("statusText", row.status() == 1 ? "生效" : "已失效");
        view.put("createdAt", row.createdAt() == null ? "" : DATETIME.format(row.createdAt()));
        return view;
    }
}
