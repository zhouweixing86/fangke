package com.fangke.visitor.admin.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
public class JdbcAdminAuditRepository implements AdminAuditRepository {

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp created = rs.getTimestamp("created_at");
            return new Row(rs.getLong("id"),
                    nullableLong(rs, "user_id"),
                    rs.getString("user_name"),
                    rs.getString("module"),
                    rs.getString("action"),
                    rs.getString("target"),
                    rs.getString("request_uri"),
                    rs.getString("request_ip"),
                    rs.getString("detail"),
                    rs.getInt("result"),
                    created == null ? null : created.toLocalDateTime());
        }

        private Long nullableLong(ResultSet rs, String column) throws SQLException {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcAdminAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> search(LocalDate from, LocalDate to, String module, String action,
                            String keyword, Long userId, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, user_id, user_name, module, action, target, request_uri, request_ip, detail, result, "
                        + "created_at FROM audit_log WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, from, to, module, action, keyword, userId);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public long count(LocalDate from, LocalDate to, String module, String action, String keyword, Long userId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM audit_log WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, from, to, module, action, keyword, userId);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<String> modules() {
        return jdbc.queryForList("SELECT DISTINCT module FROM audit_log ORDER BY module", String.class);
    }

    @Override
    public List<String> actions() {
        return jdbc.queryForList("SELECT DISTINCT action FROM audit_log ORDER BY action", String.class);
    }

    private void appendFilters(StringBuilder sql, List<Object> args, LocalDate from, LocalDate to,
                               String module, String action, String keyword, Long userId) {
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        if (module != null && !module.isBlank()) {
            sql.append(" AND module = ?");
            args.add(module.trim());
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            args.add(action.trim());
        }
        if (userId != null) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (user_name LIKE ? OR target LIKE ? OR request_uri LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
    }
}
