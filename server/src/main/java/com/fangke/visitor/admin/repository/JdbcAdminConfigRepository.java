package com.fangke.visitor.admin.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAdminConfigRepository implements AdminConfigRepository {

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp updated = rs.getTimestamp("updated_at");
            long updatedBy = rs.getLong("updated_by");
            return new Row(rs.getString("config_key"), rs.getString("config_value"),
                    rs.getInt("is_encrypted") == 1, rs.getString("remark"),
                    rs.wasNull() ? null : updatedBy,
                    updated == null ? null : updated.toLocalDateTime());
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcAdminConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> list() {
        return jdbc.query("SELECT config_key, config_value, is_encrypted, remark, updated_by, updated_at "
                + "FROM sys_config ORDER BY config_key", MAPPER);
    }

    @Override
    public Optional<Row> find(String configKey) {
        return jdbc.query("SELECT config_key, config_value, is_encrypted, remark, updated_by, updated_at "
                + "FROM sys_config WHERE config_key = ?", MAPPER, configKey).stream().findFirst();
    }

    @Override
    public void upsert(String configKey, String configValue, String remark, Long updatedBy) {
        jdbc.update("""
                INSERT INTO sys_config (config_key, config_value, is_encrypted, remark, updated_by)
                VALUES (?, ?, 0, ?, ?)
                ON DUPLICATE KEY UPDATE config_value = VALUES(config_value),
                    remark = VALUES(remark), updated_by = VALUES(updated_by)
                """, configKey, configValue, remark, updatedBy);
    }
}
