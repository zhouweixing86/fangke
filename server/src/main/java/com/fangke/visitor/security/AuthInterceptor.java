package com.fangke.visitor.security;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.admin.service.RoleAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 后台 / 保安端鉴权拦截器：校验 Bearer JWT，并把 uid、roles 放进 request attribute。
 *
 * <p>除 token 校验外还做**集中式授权**：{@code /api/admin/**} 要求 ADMIN 角色
 * （唯一例外 {@code /api/admin/user/me/**} 本人改密码）。这样即使新增管理端控制器时
 * 忘了写 {@code RoleGuard.requireAdmin}，也不会出现"任意登录账号都能调管理接口"的越权。
 *
 * <p>设备回调（/api/device/callback/**）与访客端（/api/app/**）走各自的拦截器。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "auth.userId";
    public static final String ATTR_USER_NAME = "auth.userName";
    public static final String ATTR_ROLES = "auth.roles";
    /** 角色权限并集（C-73），供菜单/接口二次判定用。 */
    public static final String ATTR_PERMS = "auth.permissions";

    private final JwtService jwtService;
    private final RoleAdminService roleAdminService;

    public AuthInterceptor(JwtService jwtService, RoleAdminService roleAdminService) {
        this.jwtService = jwtService;
        this.roleAdminService = roleAdminService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检不带 Authorization（浏览器不允许），放行交给 Spring 的 CORS 处理
        if (org.springframework.web.cors.CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String token = header.substring("Bearer ".length()).trim();
        Map<String, Object> claims = jwtService.parse(token);
        Object exp = claims.get("exp");
        long expSeconds = exp instanceof Number n ? n.longValue() : 0L;
        if (expSeconds <= java.time.Instant.now().getEpochSecond()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token 已过期");
        }
        Object uid = claims.get("uid");
        if (!(uid instanceof Number n)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        request.setAttribute(ATTR_USER_ID, n.longValue());
        request.setAttribute(ATTR_USER_NAME, claims.get("sub") instanceof String s ? s : null);
        List<String> roles = claims.get("roles") instanceof List<?> list
                ? (List<String>) list
                : List.of();
        request.setAttribute(ATTR_ROLES, roles);
        List<String> permissions = roleAdminService.permissionsOfRoles(roles);
        request.setAttribute(ATTR_PERMS, permissions);

        // 管理端接口按「模块页面权限」放行（C-73）：ADMIN 全量；其他角色看 sys_role.perms。
        // 账号改自己密码、消息中心、预约页共用的门点选项对任何已登录账号放行。
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/admin") && !roles.contains("ADMIN")) {
            String required = requiredPermission(path);
            if (required != null && !roleAdminService.hasPermission(permissions, required)) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "没有访问权限（需要 " + required + "）");
            }
        }
        return true;
    }

    /**
     * 管理端接口 → 页面权限码。
     *
     * @return {@code null} 表示"已登录即可"（改自己密码、消息中心、预约页共用门点选项）
     */
    static String requiredPermission(String path) {
        if (path.startsWith("/api/admin/user/me") || path.startsWith("/api/admin/notification/mine")
                || path.startsWith("/api/admin/gate/options")) {
            return null;
        }
        String rest = path.substring("/api/admin/".length());
        int slash = rest.indexOf('/');
        String module = slash < 0 ? rest : rest.substring(0, slash);
        return switch (module) {
            case "device", "vehicle" -> "device:read";
            case "gate" -> "gate:read";
            case "dept" -> "dept:read";
            case "employee", "upload" -> "employee:read";
            case "blacklist" -> "blacklist:read";
            case "visit" -> "visit:read";
            case "appointment" -> "appointment:read";
            case "access", "traffic" -> "event:read";
            case "sync", "dispatch" -> "batch:read";
            case "report" -> "report:read";
            case "user", "role", "roles", "wx-role-qr" -> "user:read";
            case "audit" -> "audit:read";
            case "approval" -> "approval:read";
            case "job", "config", "notification", "ops" -> "ops:read";
            default -> null;
        };
    }
}
