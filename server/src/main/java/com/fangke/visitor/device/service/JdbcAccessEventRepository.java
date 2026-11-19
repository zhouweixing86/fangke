package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.AccessEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Optional;

@Repository
public class JdbcAccessEventRepository implements AccessEventRepository {

    private static final String INSERT = "INSERT IGNORE INTO access_event "
            + "(event_uuid, device_id, device_sn, gate_id, direction, event_time, receive_time, identify_type, "
            + " person_no, person_name, id_card_mask, result_status, fail_reason, score, temperature, "
            + " mate_flag, abnormal_flag, source, raw) "
            + "VALUES (?, ?, ?, ?, ?, ?, NOW(3), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public JdbcAccessEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> insertIfAbsent(AccessEvent e) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int affected = jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, e.eventUuid());
            setNullableLong(ps, i++, e.deviceId());
            ps.setString(i++, e.deviceSn());
            setNullableLong(ps, i++, e.gateId());
            ps.setInt(i++, e.direction());
            ps.setTimestamp(i++, Timestamp.valueOf(e.eventTime()));
            ps.setInt(i++, e.identifyType());
            ps.setString(i++, e.personNo());
            ps.setString(i++, e.personName());
            ps.setString(i++, e.idCardMask());
            ps.setInt(i++, e.resultStatus());
            ps.setString(i++, e.failReason());
            ps.setBigDecimal(i++, e.score());
            ps.setBigDecimal(i++, e.temperature());
            ps.setInt(i++, e.mateFlag() ? 1 : 0);
            ps.setInt(i++, e.abnormalFlag());
            ps.setInt(i++, e.source());
            ps.setString(i, e.rawJson());
            return ps;
        }, keyHolder);

        // INSERT IGNORE 命中唯一键时 affected=0，且拿不到自增主键
        if (affected == 0) {
            return Optional.empty();
        }
        Number key = keyHolder.getKey();
        return Optional.of(key == null ? null : key.longValue());
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
