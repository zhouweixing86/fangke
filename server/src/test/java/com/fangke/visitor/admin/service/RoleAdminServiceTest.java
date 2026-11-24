package com.fangke.visitor.admin.service;

import com.fangke.visitor.admin.repository.RoleAdminRepository;
import com.fangke.visitor.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 角色权限（C-73）：通配匹配 + 权限码校验。
 */
class RoleAdminServiceTest {

    private final RoleAdminRepository repository = mock(RoleAdminRepository.class);
    private final RoleAdminService service = new RoleAdminService(repository);

    @Test
    @DisplayName("权限匹配：* 全量，module:* 匹配该模块，其它不匹配")
    void hasPermissionWildcards() {
        assertTrue(service.hasPermission(List.of("*"), "blacklist:read"));
        assertTrue(service.hasPermission(List.of("appointment:*"), "appointment:read"));
        assertTrue(service.hasPermission(List.of("appointment:read"), "appointment:read"));
        assertFalse(service.hasPermission(List.of("appointment:read"), "blacklist:read"));
        assertFalse(service.hasPermission(List.of(), "appointment:read"));
    }

    @Test
    @DisplayName("权限码校验：未知模块/非法动作直接拒绝")
    void validatePerms() {
        assertEquals(List.of("appointment:read", "guard:*"),
                service.validatePerms(List.of("appointment:read", " guard:* ")));
        assertThrows(BusinessException.class, () -> service.validatePerms(List.of("unknown:read")));
        assertThrows(BusinessException.class, () -> service.validatePerms(List.of("appointment:write")));
    }

    @Test
    @DisplayName("新增角色：编码大小写归一，重复编码报错")
    void createNormalizesCode() {
        when(repository.existsCode("HR_APPROVER")).thenReturn(false);
        when(repository.create("HR_APPROVER", "行政审批员", "只审批", "[\"appointment:read\"]", 0))
                .thenReturn(9L);

        long id = service.create("hr_approver", "行政审批员", "只审批",
                List.of("appointment:read"), 0);

        assertEquals(9L, id);
    }
}
