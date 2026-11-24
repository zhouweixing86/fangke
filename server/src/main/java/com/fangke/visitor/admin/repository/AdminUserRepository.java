package com.fangke.visitor.admin.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 后台账号管理（docs/10 §10 sys_user / sys_role / sys_user_role）。
 *
 * <p>登录态相关的查询仍在 {@code auth/repository/SysUserRepository}，这里只服务管理端 CRUD。
 */
public interface AdminUserRepository {

    record Row(Long id,
               String username,
               String realName,
               int userType,
               int status,
               LocalDateTime lastLoginAt,
               LocalDateTime createdAt,
               List<String> roles) {
    }

    record RoleRow(Long id, String roleCode, String roleName) {
    }

    List<Row> search(String keyword, Integer status, int offset, int limit);

    long count(String keyword, Integer status);

    Optional<Row> findById(Long id);

    boolean existsUsername(String username);

    String findPasswordHash(Long id);

    long insert(String username, String passwordHash, String realName, int userType);

    void updateProfile(Long id, String realName, int userType);

    void updatePassword(Long id, String passwordHash);

    void updateStatus(Long id, int status);

    void clearRoles(Long userId);

    void bindRole(Long userId, String roleCode);

    /** 覆盖式设置角色（先清后绑）。 */
    void replaceRoles(Long userId, List<String> roleCodes);

    List<RoleRow> roles();

    /** 统计某角色下仍在用的账号数（防把最后一个管理员停用）。 */
    long countActiveByRole(String roleCode);

    /**
     * 某角色下所有启用账号 id（会签节点按人建任务、抄送按角色发通知时用）。
     *
     * <p>默认返回空，方便测试替身只实现自己关心的方法。
     */
    default List<Long> findActiveUserIdsByRole(String roleCode) {
        return List.of();
    }
}
