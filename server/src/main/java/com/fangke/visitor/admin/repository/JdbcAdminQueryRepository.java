package com.fangke.visitor.admin.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAdminQueryRepository implements AdminQueryRepository {

    private static final String APPOINTMENT_SELECT = """
            SELECT a.id, a.appointment_no, v.name AS visitor_name, v.id_card_mask, a.visitor_type, a.purpose,
                   a.start_time, a.end_time, a.status, a.source, a.reject_reason, a.gate_id, a.gate_scope,
                   a.created_at,
                   (SELECT g.status FROM access_grant g WHERE g.appointment_id = a.id
                    ORDER BY g.id DESC LIMIT 1) AS grant_status,
                   (SELECT GROUP_CONCAT(gt.gate_name ORDER BY gt.sort_no, gt.id SEPARATOR '/')
                      FROM appointment_gate ag JOIN gate gt ON gt.id = ag.gate_id
                     WHERE ag.appointment_id = a.id AND ag.stage = 1) AS applied_gates,
                   (SELECT GROUP_CONCAT(gt.gate_name ORDER BY gt.sort_no, gt.id SEPARATOR '/')
                      FROM appointment_gate ag JOIN gate gt ON gt.id = ag.gate_id
                     WHERE ag.appointment_id = a.id AND ag.stage = 2) AS approved_gates
            FROM appointment a
            LEFT JOIN visitor v ON v.id = a.visitor_id
            """;

    private static final RowMapper<AppointmentRow> APPOINTMENT_MAPPER = (rs, i) -> {
        Integer grantStatus = (Integer) rs.getObject("grant_status");
        long gateId = rs.getLong("gate_id");
        Long gateIdOrNull = rs.wasNull() ? null : gateId;
        return new AppointmentRow(
                rs.getLong("id"),
                rs.getString("appointment_no"),
                rs.getString("visitor_name"),
                rs.getString("id_card_mask"),
                rs.getString("visitor_type"),
                rs.getString("purpose"),
                rs.getTimestamp("start_time").toLocalDateTime(),
                rs.getTimestamp("end_time").toLocalDateTime(),
                rs.getInt("status"),
                rs.getInt("source"),
                rs.getString("reject_reason"),
                gateIdOrNull,
                rs.getInt("gate_scope"),
                rs.getString("applied_gates"),
                rs.getString("approved_gates"),
                grantStatus,
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());
    };

    private final JdbcTemplate jdbc;

    public JdbcAdminQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AppointmentRow> searchAppointments(String keyword, Integer status, LocalDate from,
                                                   LocalDate to, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = appointmentWhere(keyword, status, from, to, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(APPOINTMENT_SELECT + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                APPOINTMENT_MAPPER, args.toArray());
    }

    @Override
    public long countAppointments(String keyword, Integer status, LocalDate from, LocalDate to) {
        List<Object> args = new ArrayList<>();
        String where = appointmentWhere(keyword, status, from, to, args);
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM appointment a "
                + "LEFT JOIN visitor v ON v.id = a.visitor_id" + where, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public Optional<AppointmentRow> findAppointment(Long id) {
        return jdbc.query(APPOINTMENT_SELECT + " WHERE a.id = ?", APPOINTMENT_MAPPER, id).stream().findFirst();
    }

    @Override
    public List<EventRow> searchEvents(LocalDate from, LocalDate to, Integer resultStatus, String personNo,
                                       int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = eventWhere(from, to, resultStatus, personNo, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("""
                        SELECT e.id, e.event_uuid, e.device_sn, e.direction, e.event_time, e.identify_type,
                               e.person_no, e.person_name, e.result_status, e.source,
                               (SELECT COUNT(1) FROM access_event_media m WHERE m.event_id = e.id) AS media_count
                        FROM access_event e
                        """ + where + " ORDER BY e.event_time DESC, e.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new EventRow(
                        rs.getLong("id"),
                        rs.getString("event_uuid"),
                        rs.getString("device_sn"),
                        rs.getInt("direction"),
                        rs.getTimestamp("event_time").toLocalDateTime(),
                        rs.getInt("identify_type"),
                        rs.getString("person_no"),
                        rs.getString("person_name"),
                        rs.getInt("result_status"),
                        rs.getInt("source"),
                        rs.getInt("media_count")),
                args.toArray());
    }

    @Override
    public long countEvents(LocalDate from, LocalDate to, Integer resultStatus, String personNo) {
        List<Object> args = new ArrayList<>();
        String where = eventWhere(from, to, resultStatus, personNo, args);
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM access_event e" + where, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /* ---------------- 条件拼装 ---------------- */

    private String appointmentWhere(String keyword, Integer status, LocalDate from, LocalDate to,
                                    List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (status != null) {
            sb.append(" AND a.status = ?");
            args.add(status);
        }
        if (StringUtils.hasText(keyword)) {
            sb.append(" AND (a.appointment_no LIKE ? OR v.name LIKE ? OR v.id_card_mask LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (from != null) {
            sb.append(" AND a.start_time >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sb.append(" AND a.start_time < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        return sb.toString();
    }

    private String eventWhere(LocalDate from, LocalDate to, Integer resultStatus, String personNo,
                              List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (from != null) {
            sb.append(" AND e.event_time >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sb.append(" AND e.event_time < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        if (resultStatus != null) {
            sb.append(" AND e.result_status = ?");
            args.add(resultStatus);
        }
        if (StringUtils.hasText(personNo)) {
            sb.append(" AND (e.person_no LIKE ? OR e.person_name LIKE ?)");
            String like = "%" + personNo.trim() + "%";
            args.add(like);
            args.add(like);
        }
        return sb.toString();
    }
}
