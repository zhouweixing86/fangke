package com.fangke.visitor.security;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 访客端/员工端（H5、小程序）鉴权：校验 App token，把角色与主体写入 request attribute。
 *
 * <p>公开接口（无需登录）：
 * <ul>
 *   <li>{@code /api/app/auth/**} 登录与验证码；</li>
 *   <li>{@code /api/app/privacy/**} 隐私同意；</li>
 *   <li>{@code /api/app/onsite/**} 现场自助登记；</li>
 *   <li>{@code GET /api/app/gate} 可选门点（未登录访客进预约页时先选门）；</li>
 *   <li>{@code POST /api/app/appointment} 访客首次预约（提交时已带身份证、手机号与隐私同意）。</li>
 * </ul>
 * 其余接口必须带 {@code Authorization: Bearer <appToken>}。
 */
@Component
public class AppAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_ROLE = "app.role";
    public static final String ATTR_SUBJECT_ID = "app.subjectId";
    public static final String ATTR_PHONE = "app.phone";

    private static final String ROLE_EMPLOYEE = "APP_EMPLOYEE";
    private static final String ROLE_VISITOR = "APP_VISITOR";

    private final JwtService jwtService;

    public AppAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (isPublic(path, request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        String token = header.substring("Bearer ".length()).trim();
        Map<String, Object> claims = jwtService.parse(token);
        Object exp = claims.get("exp");
        long expSeconds = exp instanceof Number n ? n.longValue() : 0L;
        if (expSeconds <= Instant.now().getEpochSecond()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (!(claims.get("uid") instanceof Number uid)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        List<String> roles = claims.get("roles") instanceof List<?> list ? (List<String>) list : List.of();
        String role;
        if (roles.contains(ROLE_EMPLOYEE)) {
            role = ROLE_EMPLOYEE;
        } else if (roles.contains(ROLE_VISITOR)) {
            role = ROLE_VISITOR;
        } else {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该账号不能使用访客端/员工端");
        }
        request.setAttribute(ATTR_ROLE, role);
        request.setAttribute(ATTR_SUBJECT_ID, uid.longValue());
        request.setAttribute(ATTR_PHONE, claims.get("sub") instanceof String s ? s : null);
        return true;
    }

    private boolean isPublic(String path, String method) {
        if (path.startsWith("/api/app/auth/") || path.startsWith("/api/app/privacy/")
                || path.startsWith("/api/app/onsite/")) {
            return true;
        }
        // 访客首次预约：提交时带身份证+手机号+隐私同意，不要求先登录；
        // 选门同理（门点名称不敏感），否则未登录访客进不了预约页。
        if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/app/gate")) {
            return true;
        }
        return "POST".equalsIgnoreCase(method) && "/api/app/appointment".equals(path);
    }

    public static boolean isEmployee(HttpServletRequest request) {
        return ROLE_EMPLOYEE.equals(request.getAttribute(ATTR_ROLE));
    }

    public static boolean isVisitor(HttpServletRequest request) {
        return ROLE_VISITOR.equals(request.getAttribute(ATTR_ROLE));
    }

    public static Long subjectId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTR_SUBJECT_ID);
        return id instanceof Long l ? l : null;
    }

    public static Long requireEmployee(HttpServletRequest request) {
        if (!isEmployee(request)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要员工身份");
        }
        return subjectId(request);
    }

    public static Long requireVisitor(HttpServletRequest request) {
        if (!isVisitor(request)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要访客身份");
        }
        return subjectId(request);
    }
}
