package com.fangke.visitor.security;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
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

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
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

        // 集中式管理端授权：/api/admin/** 只允许 ADMIN，避免"忘记在某个控制器里加角色校验"。
        // 唯一例外是本人改密码（任何已登录后台账号都要能改自己的）。
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/admin")
                && !path.startsWith("/api/admin/user/me/")
                && !roles.contains("ADMIN")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
        return true;
    }
}
