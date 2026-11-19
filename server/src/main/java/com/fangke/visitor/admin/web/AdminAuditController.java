package com.fangke.visitor.admin.web;

import com.fangke.visitor.admin.repository.AdminAuditRepository;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询（docs/11 §5.9）：只读，任何人（含管理员）都不能删改（BR-D-010）。
 */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AdminAuditRepository repository;

    public AdminAuditController(AdminAuditRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = repository
                .search(from, to, module, action, keyword, userId, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        long total = repository.count(from, to, module, action, keyword, userId);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, list));
    }

    /** 筛选下拉用：库里出现过的模块与动作。 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options(HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("modules", repository.modules());
        view.put("actions", repository.actions());
        return ApiResponse.ok(view);
    }

    private Map<String, Object> toView(AdminAuditRepository.Row row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("userId", row.userId() == null ? 0 : row.userId());
        view.put("userName", row.userName() == null ? "" : row.userName());
        view.put("module", row.module());
        view.put("action", row.action());
        view.put("target", row.target() == null ? "" : row.target());
        view.put("requestUri", row.requestUri() == null ? "" : row.requestUri());
        view.put("requestIp", row.requestIp() == null ? "" : row.requestIp());
        view.put("detail", row.detail() == null ? "" : row.detail());
        view.put("result", row.result());
        view.put("resultText", row.result() == 1 ? "成功" : "失败");
        view.put("createdAt", row.createdAt() == null ? "" : DATETIME.format(row.createdAt()));
        return view;
    }
}
