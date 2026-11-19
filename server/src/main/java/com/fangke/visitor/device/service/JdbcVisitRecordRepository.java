package com.fangke.visitor.device.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcVisitRecordRepository implements VisitRecordRepository {

    private final JdbcTemplate jdbc;

    public JdbcVisitRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> findAppointmentIdByPersonNo(String personNo) {
        List<Long> ids = jdbc.query(
                "SELECT appointment_id FROM access_grant WHERE person_no = ? AND appointment_id IS NOT NULL "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), personNo);
        return ids.stream().findFirst();
    }

    @Override
    public Optional<Long> findOpenRecordId(Long appointmentId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM visit_record WHERE appointment_id = ? AND checkout_at IS NULL ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), appointmentId);
        return ids.stream().findFirst();
    }

    @Override
    public Optional<Long> findAnyRecordId(Long appointmentId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM visit_record WHERE appointment_id = ? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), appointmentId);
        return ids.stream().findFirst();
    }

    @Override
    public long insertCheckin(Long appointmentId, Long visitorId, Long gateId,
                              LocalDateTime checkinAt, Long checkinEvent, LocalDate visitDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO visit_record (appointment_id, visitor_id, gate_id, checkin_at, checkin_event, "
                            + "visit_date, status) VALUES (?, ?, ?, ?, ?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, appointmentId);
            if (visitorId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, visitorId);
            }
            ps.setLong(3, gateId);
            ps.setTimestamp(4, Timestamp.valueOf(checkinAt));
            ps.setLong(5, checkinEvent);
            ps.setDate(6, java.sql.Date.valueOf(visitDate));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("签到记录写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateCheckout(long recordId, LocalDateTime checkoutAt, Long checkoutEvent,
                               int checkoutType, int status) {
        jdbc.update("UPDATE visit_record SET checkout_at = ?, checkout_event = ?, checkout_type = ?, status = ? "
                        + "WHERE id = ?",
                Timestamp.valueOf(checkoutAt), checkoutEvent, checkoutType, status, recordId);
    }

    @Override
    public void updateAppointmentStatus(Long appointmentId, int status) {
        jdbc.update("UPDATE appointment SET status = ? WHERE id = ?", status, appointmentId);
    }

    @Override
    public java.util.List<OverdueRecord> findOverdueOpen(LocalDateTime before, int limit) {
        return jdbc.query("""
                        SELECT v.id AS record_id, v.appointment_id
                        FROM visit_record v
                        JOIN appointment a ON a.id = v.appointment_id
                        WHERE v.status = 1 AND a.end_time < ?
                        ORDER BY a.end_time
                        LIMIT ?
                        """,
                (rs, i) -> new OverdueRecord(rs.getLong("record_id"), rs.getLong("appointment_id")),
                Timestamp.valueOf(before), limit);
    }
}
