package com.fangke.visitor.security;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 角色校验小工具：管理类接口（账号、审计、报表）只有 ADMIN 能进。
 *
 * <p>角色来自 JWT 里的 {@code roles}，改角色后需重新登录才生效（token 有效期 8 小时）。
 */
public final class RoleGuard {

    private RoleGuard() {
    }

    public static void requireAdmin(HttpServletRequest request) {
        if (!hasRole(request, "ADMIN")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }

    public static void requireGuardOrAdmin(HttpServletRequest request) {
        if (!hasRole(request, "GUARD") && !hasRole(request, "ADMIN")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要保安或管理员角色");
        }
    }

    public static boolean hasRole(HttpServletRequest request, String role) {
        Object roles = request.getAttribute(AuthInterceptor.ATTR_ROLES);
        return roles instanceof List<?> list && list.contains(role);
    }

    /** 当前登录账号的角色列表（可能是 ADMIN / GUARD / VISITOR_ADMIN）。 */
    @SuppressWarnings("unchecked")
    public static List<String> roles(HttpServletRequest request) {
        Object roles = request.getAttribute(AuthInterceptor.ATTR_ROLES);
        return roles instanceof List<?> list ? (List<String>) list : List.of();
    }

    public static Long currentUserId(HttpServletRequest request) {
        Object uid = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return uid instanceof Long l ? l : null;
    }
}
