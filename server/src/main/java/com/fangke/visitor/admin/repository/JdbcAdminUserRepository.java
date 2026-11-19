package com.fangke.visitor.admin.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcAdminUserRepository implements AdminUserRepository {

    private static final String SELECT = """
            SELECT id, username, real_name, user_type, status, last_login_at, created_at
            FROM sys_user WHERE deleted_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public JdbcAdminUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> search(String keyword, Integer status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, status);
        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        List<Row> base = jdbc.query(sql.toString(), BASE_MAPPER, args.toArray());
        Map<Long, List<String>> roles = rolesOf(base.stream().map(Row::id).toList());
        return base.stream()
                .map(r -> new Row(r.id(), r.username(), r.realName(), r.userType(), r.status(),
                        r.lastLoginAt(), r.createdAt(), roles.getOrDefault(r.id(), List.of())))
                .toList();
    }

    @Override
    public long count(String keyword, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM sys_user WHERE deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, status);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Row> findById(Long id) {
        return jdbc.query(SELECT + " AND id = ?", BASE_MAPPER, id).stream()
                .map(r -> new Row(r.id(), r.username(), r.realName(), r.userType(), r.status(),
                        r.lastLoginAt(), r.createdAt(), rolesOf(List.of(r.id())).getOrDefault(r.id(), List.of())))
                .findFirst();
    }

    @Override
    public boolean existsUsername(String username) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE username = ? AND deleted_at IS NULL", Long.class, username);
        return count != null && count > 0;
    }

    @Override
    public String findPasswordHash(Long id) {
        return jdbc.query("SELECT password_hash FROM sys_user WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> rs.getString(1), id).stream().findFirst().orElse(null);
    }

    @Override
    public long insert(String username, String passwordHash, String realName, int userType) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sys_user (username, password_hash, real_name, user_type, status) "
                            + "VALUES (?, ?, ?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, realName);
            ps.setInt(4, userType);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("账号写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateProfile(Long id, String realName, int userType) {
        jdbc.update("UPDATE sys_user SET real_name = ?, user_type = ? WHERE id = ?", realName, userType, id);
    }

    @Override
    public void updatePassword(Long id, String passwordHash) {
        jdbc.update("UPDATE sys_user SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    @Override
    public void updateStatus(Long id, int status) {
        jdbc.update("UPDATE sys_user SET status = ? WHERE id = ?", status, id);
    }

    @Override
    public void clearRoles(Long userId) {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
    }

    @Override
    public void bindRole(Long userId, String roleCode) {
        jdbc.update("""
                INSERT IGNORE INTO sys_user_role (user_id, role_id)
                SELECT ?, id FROM sys_role WHERE role_code = ?
                """, userId, roleCode);
    }

    @Override
    public List<RoleRow> roles() {
        return jdbc.query("SELECT id, role_code, role_name FROM sys_role ORDER BY id",
                (rs, i) -> new RoleRow(rs.getLong("id"), rs.getString("role_code"), rs.getString("role_name")));
    }

    @Override
    public long countActiveByRole(String roleCode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT u.id) FROM sys_user u
                JOIN sys_user_role ur ON ur.user_id = u.id
                JOIN sys_role r ON r.id = ur.role_id
                WHERE r.role_code = ? AND u.status = 1 AND u.deleted_at IS NULL
                """, Long.class, roleCode);
        return count == null ? 0 : count;
    }

    @Override
    public void replaceRoles(Long userId, List<String> roleCodes) {
        clearRoles(userId);
        roleCodes.forEach(code -> bindRole(userId, code));
    }

    private Map<Long, List<String>> rolesOf(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", userIds.stream().map(id -> "?").toList());
        Map<Long, List<String>> result = new HashMap<>();
        jdbc.query("""
                SELECT ur.user_id, r.role_code FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id IN (""" + placeholders + ") ORDER BY r.id",
                rs -> {
                    Long userId = rs.getLong("user_id");
                    result.computeIfAbsent(userId, k -> new ArrayList<>()).add(rs.getString("role_code"));
                }, userIds.toArray());
        return result;
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String keyword, Integer status) {
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username LIKE ? OR real_name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
    }

    private static final RowMapper<Row> BASE_MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp login = rs.getTimestamp("last_login_at");
            Timestamp created = rs.getTimestamp("created_at");
            return new Row(rs.getLong("id"), rs.getString("username"), rs.getString("real_name"),
                    rs.getInt("user_type"), rs.getInt("status"),
                    login == null ? null : login.toLocalDateTime(),
                    created == null ? null : created.toLocalDateTime(),
                    Arrays.asList());
        }
    };
}
