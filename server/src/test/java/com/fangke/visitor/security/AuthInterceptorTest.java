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
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtService);

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
        assertTrue(e.getMessage().contains("管理员"));
    }

    @Test
    @DisplayName("GUARD 访问保安端：放行")
    void guardAllowedOnGuardPath() {
        stubToken("guard-token", List.of("GUARD"));

        assertTrue(interceptor.preHandle(request("/api/guard/dashboard", "guard-token"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("别名 VISITOR_ADMIN 也不能进管理端（只认 ADMIN）")
    void visitorAdminForbidden() {
        stubToken("va-token", List.of("VISITOR_ADMIN"));

        assertThrows(BusinessException.class, () -> interceptor.preHandle(
                request("/api/admin/appointment", "va-token"), new MockHttpServletResponse(), new Object()));
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
}
