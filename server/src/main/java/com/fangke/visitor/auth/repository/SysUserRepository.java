package com.fangke.visitor.auth.repository;

import java.util.List;
import java.util.Optional;

/**
 * 后台账号仓储（docs/10 §10 sys_user）。
 */
public interface SysUserRepository {

    record UserRow(Long id, String username, String passwordHash, String realName,
                   int userType, int status) {
    }

    Optional<UserRow> findByUsername(String username);

    List<String> findRoles(Long userId);

    long countUsers();

    long insert(String username, String passwordHash, String realName, int userType);

    /** 绑定角色（按角色编码）。 */
    void bindRole(Long userId, String roleCode);
}
