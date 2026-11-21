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
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAdminBlacklistRepository implements AdminBlacklistRepository {

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Row(
                    rs.getLong("id"),
                    rs.getInt("target_type"),
                    rs.getString("target_mask"),
                    rs.getString("reason"),
                    toLocal(rs.getTimestamp("valid_from")),
                    toLocal(rs.getTimestamp("valid_to")),
                    rs.getInt("status"),
                    nullableLong(rs, "created_by"),
                    toLocal(rs.getTimestamp("created_at")));
        }

        private LocalDateTime toLocal(Timestamp ts) {
            return ts == null ? null : ts.toLocalDateTime();
        }

        private Long nullableLong(ResultSet rs, String column) throws SQLException {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcAdminBlacklistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> search(String keyword, Integer status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, target_type, target_mask, reason, valid_from, valid_to, status, created_by, created_at "
                        + "FROM blacklist WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, status);
        sql.append(" ORDER BY status DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public long count(String keyword, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM blacklist WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, status);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Row> findById(Long id) {
        return jdbc.query("SELECT id, target_type, target_mask, reason, valid_from, valid_to, status, created_by, "
                + "created_at FROM blacklist WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<String> findTargetHmac(Long id) {
        return jdbc.query("SELECT target_hmac FROM blacklist WHERE id = ?",
                (rs, i) -> rs.getString(1), id).stream().findFirst();
    }

    @Override
    public Optional<byte[]> findTargetEnc(Long id) {
        return jdbc.query("SELECT target_enc FROM blacklist WHERE id = ?",
                (rs, i) -> rs.getBytes(1), id).stream().findFirst();
    }

    @Override
    public List<byte[]> findActiveIdCardTargets() {
        return jdbc.query("""
                        SELECT target_enc FROM blacklist
                        WHERE target_type = 1 AND status = 1
                          AND (valid_from IS NULL OR valid_from <= NOW())
                          AND (valid_to IS NULL OR valid_to >= NOW())
                        """,
                (rs, i) -> rs.getBytes(1));
    }

    @Override
    public boolean existsEffective(int targetType, String targetHmac) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM blacklist WHERE target_type = ? AND target_hmac = ? AND status = 1",
                Long.class, targetType, targetHmac);
        return count != null && count > 0;
    }

    @Override
    public long insert(int targetType, byte[] targetEnc, String targetHmac, String targetMask,
                       String reason, LocalDateTime validFrom, LocalDateTime validTo, Long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO blacklist (target_type, target_enc, target_hmac, target_mask, reason,
                        valid_from, valid_to, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, targetType);
            ps.setBytes(2, targetEnc);
            ps.setString(3, targetHmac);
            ps.setString(4, targetMask);
            ps.setString(5, reason);
            setNullableTs(ps, 6, validFrom);
            setNullableTs(ps, 7, validTo);
            if (createdBy == null) {
                ps.setNull(8, Types.BIGINT);
            } else {
                ps.setLong(8, createdBy);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("黑名单写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateStatus(Long id, int status) {
        jdbc.update("UPDATE blacklist SET status = ? WHERE id = ?", status, id);
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String keyword, Integer status) {
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (target_mask LIKE ? OR reason LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
    }

    private void setNullableTs(PreparedStatement ps, int index, LocalDateTime value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(value));
        }
    }
}
