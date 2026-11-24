package com.fangke.visitor.admin.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class JdbcRoleAdminRepository implements RoleAdminRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RowMapper<RoleDetail> MAPPER_ROW = (rs, i) -> new RoleDetail(
            rs.getLong("id"),
            rs.getString("role_code"),
            rs.getString("role_name"),
            rs.getString("description"),
            rs.getString("perms"),
            rs.getInt("builtin") == 1,
            rs.getInt("status"),
            rs.getInt("sort"),
            rs.getLong("user_count"));

    private final JdbcTemplate jdbc;

    public JdbcRoleAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RoleDetail> list() {
        return jdbc.query("""
                SELECT r.id, r.role_code, r.role_name, r.description, r.perms, r.builtin, r.status, r.sort,
                       (SELECT COUNT(1) FROM sys_user_role ur WHERE ur.role_id = r.id) AS user_count
                FROM sys_role r
                ORDER BY r.sort, r.id
                """, MAPPER_ROW);
    }

    @Override
    public Optional<RoleDetail> findById(Long id) {
        return jdbc.query("""
                SELECT r.id, r.role_code, r.role_name, r.description, r.perms, r.builtin, r.status, r.sort,
                       (SELECT COUNT(1) FROM sys_user_role ur WHERE ur.role_id = r.id) AS user_count
                FROM sys_role r WHERE r.id = ?
                """, MAPPER_ROW, id).stream().findFirst();
    }

    @Override
    public boolean existsCode(String roleCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_role WHERE role_code = ?", Integer.class, roleCode);
        return count != null && count > 0;
    }

    @Override
    public long create(String roleCode, String roleName, String description, String permsJson, int sort) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO sys_role (role_code, role_name, description, perms, builtin, status, sort)
                    VALUES (?, ?, ?, ?, 0, 1, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, roleCode);
            ps.setString(2, roleName);
            ps.setString(3, description);
            ps.setString(4, permsJson);
            ps.setInt(5, sort);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("角色写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void update(Long id, String roleName, String description, String permsJson,
                       Integer status, int sort) {
        jdbc.update("""
                        UPDATE sys_role SET role_name = ?, description = ?, perms = ?,
                            status = COALESCE(?, status), sort = ? WHERE id = ?
                        """,
                roleName, description, permsJson, status, sort, id);
    }

    @Override
    public void delete(Long id) {
        jdbc.update("DELETE FROM sys_role WHERE id = ? AND builtin = 0", id);
    }

    @Override
    public List<String> permissionsOfRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(roleCodes.size(), "?"));
        List<String> raw = jdbc.queryForList(
                "SELECT perms FROM sys_role WHERE status = 1 AND role_code IN (" + placeholders + ")",
                String.class, roleCodes.toArray());
        Set<String> merged = new LinkedHashSet<>();
        for (String json : raw) {
            merged.addAll(parse(json));
        }
        return new ArrayList<>(merged);
    }

    /** 解析 perms JSON；历史脏数据按空处理，不让权限解析把登录打挂。 */
    private List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
