package com.fangke.visitor.admin.repository;

import java.util.List;
import java.util.Optional;

/**
 * 角色与权限（C-73）：管理端可自由新增角色，并给角色分配页面/接口权限。
 *
 * <p>权限存在 {@code sys_role.perms}（JSON 数组），码值形如 {@code appointment:read}、
 * {@code guard:*}、{@code *}；多个角色的权限取并集。
 */
public interface RoleAdminRepository {

    record RoleDetail(Long id, String roleCode, String roleName, String description,
                      String permsJson, boolean builtin, int status, int sort, long userCount) {
    }

    List<RoleDetail> list();

    Optional<RoleDetail> findById(Long id);

    boolean existsCode(String roleCode);

    long create(String roleCode, String roleName, String description, String permsJson, int sort);

    void update(Long id, String roleName, String description, String permsJson, Integer status, int sort);

    void delete(Long id);

    /** 给定角色码，返回它们的权限并集（去重、保持顺序）。 */
    List<String> permissionsOfRoles(List<String> roleCodes);
}
