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
        if (hasRole(request, "GUARD") || hasRole(request, "ADMIN")
                || hasPerm(request, "dashboard:read") || hasPerm(request, "guard:*")) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN,
                "需要保安/管理员角色，或「保安工作台」页面权限");
    }

    /** 当前账号是否具备某权限（支持 module:* 与 *，C-73）。 */
    public static boolean hasPerm(HttpServletRequest request, String perm) {
        Object perms = request.getAttribute(AuthInterceptor.ATTR_PERMS);
        if (!(perms instanceof List<?> list)) {
            return false;
        }
        if (list.contains("*") || list.contains(perm)) {
            return true;
        }
        String module = perm.contains(":") ? perm.substring(0, perm.indexOf(':')) : perm;
        return list.contains(module + ":*");
    }

    /**
     * 审批接口专用：任何**已登录后台账号**都能进。
     *
     * <p>多级审批的节点可以配任意角色（C-73 后角色可自定义），所以这里不能写死 GUARD；
     * 具体能看到哪些任务由 {@code approval_task.assignee_role} 过滤，越权看不到别人的待办。
     */
    public static void requireApprovalUser(HttpServletRequest request) {
        if (currentUserId(request) == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
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
