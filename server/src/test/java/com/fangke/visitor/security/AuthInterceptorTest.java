package com.fangke.visitor.security;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后台拦截器：token 校验 + 集中式「/api/admin/** 需要 ADMIN」。
 *
 * <p>这条规则是为了防止"新增管理端控制器忘了写角色校验"导致越权（真实缺陷：
 * AdminBlacklistController 曾只校验登录、不校验角色）。
 */
class AuthInterceptorTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtService, roleService());

    /** 真实权限服务 + 假角色仓储：ADMIN 全量、GUARD 只读审批/预约、VISITOR_ADMIN 只读预约。 */
    private com.fangke.visitor.admin.service.RoleAdminService roleService() {
        var repo = mock(com.fangke.visitor.admin.repository.RoleAdminRepository.class);
        when(repo.permissionsOfRoles(List.of("ADMIN"))).thenReturn(List.of("*"));
        when(repo.permissionsOfRoles(List.of("GUARD")))
                .thenReturn(List.of("guard:*", "appointment:read", "event:read"));
        when(repo.permissionsOfRoles(List.of("VISITOR_ADMIN")))
                .thenReturn(List.of("appointment:read"));
        return new com.fangke.visitor.admin.service.RoleAdminService(repo);
    }

    private MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }

    private void stubToken(String token, List<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", 7L);
        claims.put("sub", "tester");
        claims.put("roles", roles);
        claims.put("exp", Instant.now().getEpochSecond() + 3600);
        when(jwtService.parse(token)).thenReturn(claims);
    }

    @Test
    @DisplayName("ADMIN 访问管理端：放行并写入 uid/角色")
    void adminAllowed() {
        stubToken("admin-token", List.of("ADMIN"));
        MockHttpServletRequest request = request("/api/admin/employee", "admin-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(7L, request.getAttribute(AuthInterceptor.ATTR_USER_ID));
        assertEquals("tester", request.getAttribute(AuthInterceptor.ATTR_USER_NAME));
    }

    @Test
    @DisplayName("GUARD 访问管理端：403（集中式规则，不依赖控制器自觉）")
    void guardForbiddenOnAdminPath() {
        stubToken("guard-token", List.of("GUARD"));
        MockHttpServletRequest request = request("/api/admin/blacklist", "guard-token");

        BusinessException e = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(ErrorCode.FORBIDDEN.code(), e.getCode());
        assertTrue(e.getMessage().contains("blacklist:read"));
    }

    @Test
    @DisplayName("GUARD 访问保安端：放行")
    void guardAllowedOnGuardPath() {
        stubToken("guard-token", List.of("GUARD"));

        assertTrue(interceptor.preHandle(request("/api/guard/dashboard", "guard-token"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("角色权限生效：有 appointment:read 可看预约，没有 blacklist:read 进不去黑名单")
    void rolePermissionGatesAdminApi() {
        stubToken("va-token", List.of("VISITOR_ADMIN"));

        assertTrue(interceptor.preHandle(request("/api/admin/appointment", "va-token"),
                new MockHttpServletResponse(), new Object()));

        BusinessException e = assertThrows(BusinessException.class, () -> interceptor.preHandle(
                request("/api/admin/blacklist", "va-token"), new MockHttpServletResponse(), new Object()));
        assertEquals(ErrorCode.FORBIDDEN.code(), e.getCode());
        assertTrue(e.getMessage().contains("blacklist:read"));
    }

    @Test
    @DisplayName("本人改密码是例外：任何已登录账号都能调")
    void ownPasswordExempt() {
        stubToken("guard-token", List.of("GUARD"));

        assertTrue(interceptor.preHandle(request("/api/admin/user/me/password", "guard-token"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("缺少 Authorization：401")
    void missingToken() {
        BusinessException e = assertThrows(BusinessException.class, () -> interceptor.preHandle(
                request("/api/admin/employee", null), new MockHttpServletResponse(), new Object()));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), e.getCode());
    }

    @Test
    @DisplayName("token 过期：401")
    void expiredToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", 7L);
        claims.put("roles", List.of("ADMIN"));
        claims.put("exp", Instant.now().getEpochSecond() - 10);
        when(jwtService.parse("old-token")).thenReturn(claims);

        BusinessException e = assertThrows(BusinessException.class, () -> interceptor.preHandle(
                request("/api/admin/employee", "old-token"), new MockHttpServletResponse(), new Object()));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), e.getCode());
        assertTrue(e.getMessage().contains("过期"));
    }

    @Test
    @DisplayName("CORS 预检（OPTIONS）不带 token 也要放行，否则跨域请求全部 500")
    void preflightBypassesAuth() {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/admin/device");
        preflight.setRequestURI("/api/admin/device");
        preflight.addHeader("Origin", "http://192.168.1.131:5175");
        preflight.addHeader("Access-Control-Request-Method", "GET");
        preflight.addHeader("Access-Control-Request-Headers", "authorization");

        assertTrue(interceptor.preHandle(preflight, new MockHttpServletResponse(), new Object()));
    }
}
