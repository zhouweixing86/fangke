package com.fangke.visitor.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSysUserRepository implements SysUserRepository {

    private final JdbcTemplate jdbc;

    public JdbcSysUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserRow> findByUsername(String username) {
        return jdbc.query("""
                        SELECT id, username, password_hash, real_name, user_type, status
                        FROM sys_user WHERE username = ? AND deleted_at IS NULL LIMIT 1
                        """,
                (rs, i) -> new UserRow(
                        rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("real_name"), rs.getInt("user_type"), rs.getInt("status")),
                username).stream().findFirst();
    }

    @Override
    public List<String> findRoles(Long userId) {
        return jdbc.query("""
                        SELECT r.role_code FROM sys_user_role ur
                        JOIN sys_role r ON r.id = ur.role_id
                        WHERE ur.user_id = ?
                        """,
                (rs, i) -> rs.getString(1), userId);
    }

    @Override
    public long countUsers() {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM sys_user WHERE deleted_at IS NULL", Long.class);
        return count == null ? 0 : count;
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
    public void bindRole(Long userId, String roleCode) {
        jdbc.update("""
                INSERT IGNORE INTO sys_user_role (user_id, role_id)
                SELECT ?, id FROM sys_role WHERE role_code = ?
                """, userId, roleCode);
    }
}
