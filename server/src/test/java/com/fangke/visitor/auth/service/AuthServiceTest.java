package com.fangke.visitor.auth.service;

import com.fangke.visitor.auth.repository.SysUserRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    static class FakeRepo implements SysUserRepository {
        final Map<String, UserRow> users = new LinkedHashMap<>();
        final Map<Long, List<String>> roles = new LinkedHashMap<>();

        @Override
        public Optional<UserRow> findByUsername(String username) {
            return Optional.ofNullable(users.get(username));
        }

        @Override
        public List<String> findRoles(Long userId) {
            return roles.getOrDefault(userId, List.of());
        }

        @Override
        public long countUsers() {
            return users.size();
        }

        @Override
        public long insert(String username, String passwordHash, String realName, int userType) {
            long id = users.size() + 1;
            users.put(username, new UserRow(id, username, passwordHash, realName, userType, 1));
            return id;
        }

        @Override
        public void bindRole(Long userId, String roleCode) {
            roles.put(userId, List.of(roleCode));
        }
    }

    private FakeRepo repo;
    private AuthService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        AppProperties props = new AppProperties();
        props.getJwt().setSecret("unit-jwt-secret");
        service = new AuthService(repo, new JwtService(props));

        long id = repo.insert("admin", service.encode("Admin@123456"), "系统管理员", 1);
        repo.bindRole(id, "ADMIN");
    }

    @Test
    @DisplayName("登录成功：返回 token 与角色，token 可用 JWT 服务校验")
    void loginSuccess() {
        AuthService.LoginResult result = service.login("admin", "Admin@123456");

        assertNotNull(result.token());
        assertEquals("ADMIN", result.roles().get(0));
        assertEquals("系统管理员", result.displayName());
        assertTrue(result.expiresInSeconds() > 0);
    }

    @Test
    @DisplayName("密码错误：统一提示，不泄露用户是否存在")
    void wrongPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.login("admin", "wrong"));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), e.getCode());
        assertEquals("用户名或密码错误", e.getMessage());

        BusinessException noUser = assertThrows(BusinessException.class,
                () -> service.login("nobody", "x"));
        assertEquals("用户名或密码错误", noUser.getMessage());
    }

    @Test
    @DisplayName("账号停用：返回 2003")
    void disabled() {
        SysUserRepository.UserRow row = repo.users.get("admin");
        repo.users.put("admin", new SysUserRepository.UserRow(row.id(), row.username(), row.passwordHash(),
                row.realName(), row.userType(), 0));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.login("admin", "Admin@123456"));
        assertEquals(ErrorCode.ACCOUNT_DISABLED.code(), e.getCode());
    }

    @Test
    @DisplayName("连续失败 5 次后锁定，提示稍后再试")
    void lockoutAfterFailures() {
        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessException.class, () -> service.login("admin", "bad"));
        }
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.login("admin", "Admin@123456"));
        assertTrue(e.getMessage().contains("尝试次数过多"), e.getMessage());
    }

    @Test
    @DisplayName("空用户名/密码直接拒绝（1001）")
    void blankInput() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.login(" ", ""));
        assertEquals(ErrorCode.PARAM_MISSING.code(), e.getCode());
    }
}
