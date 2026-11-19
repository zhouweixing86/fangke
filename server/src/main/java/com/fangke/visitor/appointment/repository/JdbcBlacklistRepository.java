package com.fangke.visitor.appointment.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Repository
public class JdbcBlacklistRepository implements BlacklistRepository {

    private static final String SQL = """
            SELECT COUNT(1) FROM blacklist
            WHERE target_type = ? AND target_hmac = ? AND status = 1
              AND (valid_from IS NULL OR valid_from <= ?)
              AND (valid_to IS NULL OR valid_to >= ?)
            """;

    private final JdbcTemplate jdbc;

    public JdbcBlacklistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isBlacklisted(int targetType, String targetHmac, LocalDateTime at) {
        if (targetHmac == null) {
            return false;
        }
        Timestamp ts = Timestamp.valueOf(at);
        Long count = jdbc.queryForObject(SQL, Long.class, targetType, targetHmac, ts, ts);
        return count != null && count > 0;
    }
}
