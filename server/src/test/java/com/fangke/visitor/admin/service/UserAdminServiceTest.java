package com.fangke.visitor.admin.service;

import com.fangke.visitor.admin.repository.AdminUserRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号与角色管理：校验、口令强度、最后一个管理员保护、自助改密码。
 */
class UserAdminServiceTest {

    private FakeRepo repo;
    private UserAdminService service;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        service = new UserAdminService(repo);
    }

    @Test
    @DisplayName("新建账号：BCrypt 存口令、角色按编码绑定")
    void createOk() {
        Long id = service.create("guard01", "保安小王", 3, "Guard123456", List.of("guard"));

        AdminUserRepository.Row row = repo.findById(id).orElseThrow();
        assertEquals("guard01", row.username());
        assertEquals(3, row.userType());
        assertEquals(List.of("GUARD"), row.roles());
        assertTrue(encoder.matches("Guard123456", repo.hashOf(id)));
        assertFalse(repo.hashOf(id).equals("Guard123456"));
    }

    @Test
    @DisplayName("账号重复/格式非法：拒绝")
    void createValidatesUsername() {
        service.create("admin01", "管理员", 1, "Admin123456", List.of("ADMIN"));

        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create("admin01", "管理员2", 1, "Admin123456", List.of("ADMIN"))).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create("a b", "管理员", 1, "Admin123456", List.of("ADMIN"))).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create("ab", "管理员", 1, "Admin123456", List.of("ADMIN"))).getCode());
    }

    @Test
    @DisplayName("口令强度：<8 位、纯字母、纯数字都拒绝")
    void createValidatesPassword() {
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create("user01", "用户", 3, "Abc123", List.of("GUARD"))).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create("user01", "用户", 3, "abcdefgh", List.of("GUARD"))).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create("user01", "用户", 3, "12345678", List.of("GUARD"))).getCode());
    }

    @Test
    @DisplayName("角色：必填、必须来自字典")
    void createValidatesRoles() {
        assertEquals(ErrorCode.PARAM_MISSING.code(), assertThrows(BusinessException.class,
                () -> service.create("user01", "用户", 3, "Guard123456", List.of())).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create("user01", "用户", 3, "Guard123456", List.of("SUPER"))).getCode());
    }

    @Test
    @DisplayName("不能移除最后一个管理员的管理员角色")
    void cannotDemoteLastAdmin() {
        Long adminId = service.create("admin01", "管理员", 1, "Admin123456", List.of("ADMIN"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(adminId, "管理员", 1, List.of("GUARD"), adminId));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), e.getCode());
        assertTrue(repo.findById(adminId).orElseThrow().roles().contains("ADMIN"));
    }

    @Test
    @DisplayName("有第二个管理员时可以降权")
    void canDemoteWhenAnotherAdminExists() {
        Long first = service.create("admin01", "管理员一", 1, "Admin123456", List.of("ADMIN"));
        service.create("admin02", "管理员二", 1, "Admin123456", List.of("ADMIN"));

        service.update(first, "管理员一", 1, List.of("GUARD"), first);
        assertEquals(List.of("GUARD"), repo.findById(first).orElseThrow().roles());
    }

    @Test
    @DisplayName("不能停用自己，也不能停用最后一个管理员")
    void cannotDisableSelfOrLastAdmin() {
        Long adminId = service.create("admin01", "管理员", 1, "Admin123456", List.of("ADMIN"));

        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), assertThrows(BusinessException.class,
                () -> service.changeStatus(adminId, 0, adminId)).getCode());

        Long other = service.create("guard01", "保安", 3, "Guard123456", List.of("GUARD"));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), assertThrows(BusinessException.class,
                () -> service.changeStatus(adminId, 0, other)).getCode());

        service.changeStatus(other, 0, adminId);
        assertEquals(0, repo.findById(other).orElseThrow().status());
    }

    @Test
    @DisplayName("管理员重置密码：新口令可登录、旧口令失效")
    void resetPassword() {
        Long id = service.create("guard01", "保安", 3, "Guard123456", List.of("GUARD"));

        service.resetPassword(id, "NewGuard8888");
        assertTrue(encoder.matches("NewGuard8888", repo.hashOf(id)));
        assertFalse(encoder.matches("Guard123456", repo.hashOf(id)));
    }

    @Test
    @DisplayName("自助改密码：旧口令错误/新旧相同都拒绝")
    void changeOwnPassword() {
        Long id = service.create("guard01", "保安", 3, "Guard123456", List.of("GUARD"));

        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.changeOwnPassword(id, "wrong", "NewGuard8888")).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.changeOwnPassword(id, "Guard123456", "Guard123456")).getCode());

        service.changeOwnPassword(id, "Guard123456", "NewGuard8888");
        assertTrue(encoder.matches("NewGuard8888", repo.hashOf(id)));
    }

    @Test
    @DisplayName("账号不存在：明确报错")
    void unknownUser() {
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.changeStatus(999L, 0, 1L)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.resetPassword(999L, "Abcd1234")).getCode());
    }

    /* ---------------- 假仓储 ---------------- */

    static class FakeRepo implements AdminUserRepository {

        final Map<Long, Row> rows = new LinkedHashMap<>();
        final Map<Long, String> hashes = new LinkedHashMap<>();
        private long seq = 0;

        @Override
        public List<Row> search(String keyword, Integer status, int offset, int limit) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public long count(String keyword, Integer status) {
            return rows.size();
        }

        @Override
        public Optional<Row> findById(Long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean existsUsername(String username) {
            return rows.values().stream().anyMatch(r -> r.username().equalsIgnoreCase(username));
        }

        @Override
        public String findPasswordHash(Long id) {
            return hashes.get(id);
        }

        @Override
        public long insert(String username, String passwordHash, String realName, int userType) {
            long id = ++seq;
            hashes.put(id, passwordHash);
            rows.put(id, new Row(id, username, realName, userType, 1, null, LocalDateTime.now(), List.of()));
            return id;
        }

        @Override
        public void updateProfile(Long id, String realName, int userType) {
            Row r = rows.get(id);
            rows.put(id, new Row(r.id(), r.username(), realName, userType, r.status(),
                    r.lastLoginAt(), r.createdAt(), r.roles()));
        }

        @Override
        public void updatePassword(Long id, String passwordHash) {
            hashes.put(id, passwordHash);
        }

        @Override
        public void updateStatus(Long id, int status) {
            Row r = rows.get(id);
            rows.put(id, new Row(r.id(), r.username(), r.realName(), r.userType(), status,
                    r.lastLoginAt(), r.createdAt(), r.roles()));
        }

        @Override
        public void clearRoles(Long userId) {
            replace(userId, List.of());
        }

        @Override
        public void bindRole(Long userId, String roleCode) {
            List<String> roles = new ArrayList<>(rows.get(userId).roles());
            roles.add(roleCode);
            replace(userId, roles);
        }

        @Override
        public void replaceRoles(Long userId, List<String> roleCodes) {
            replace(userId, roleCodes);
        }

        @Override
        public List<RoleRow> roles() {
            return List.of(new RoleRow(1L, "ADMIN", "系统管理员"),
                    new RoleRow(2L, "GUARD", "保安"),
                    new RoleRow(3L, "VISITOR_ADMIN", "访客管理员"));
        }

        @Override
        public long countActiveByRole(String roleCode) {
            return rows.values().stream()
                    .filter(r -> r.status() == 1 && r.roles().contains(roleCode))
                    .count();
        }

        String hashOf(Long id) {
            return hashes.get(id);
        }

        private void replace(Long userId, List<String> roleCodes) {
            Row r = rows.get(userId);
            rows.put(userId, new Row(r.id(), r.username(), r.realName(), r.userType(), r.status(),
                    r.lastLoginAt(), r.createdAt(), List.copyOf(roleCodes)));
        }
    }
}
