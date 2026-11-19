package com.fangke.visitor.guard.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcGuardQueryRepository implements GuardQueryRepository {

    private final JdbcTemplate jdbc;

    public JdbcGuardQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Counts counts() {
        Long onsite = jdbc.queryForObject("SELECT COUNT(1) FROM visit_record WHERE status = 1", Long.class);
        Long pending = jdbc.queryForObject("SELECT COUNT(1) FROM appointment WHERE status IN (20, 25)", Long.class);
        Long today = jdbc.queryForObject("SELECT COUNT(1) FROM visit_record WHERE visit_date = CURDATE()",
                Long.class);
        Integer total = jdbc.queryForObject("SELECT COUNT(1) FROM device WHERE status = 1", Integer.class);
        Integer online = jdbc.queryForObject(
                "SELECT COALESCE(SUM(online_state = 1), 0) FROM device WHERE status = 1", Integer.class);
        return new Counts(
                onsite == null ? 0 : onsite.intValue(),
                pending == null ? 0 : pending.intValue(),
                online == null ? 0 : online,
                total == null ? 0 : total,
                today == null ? 0 : today.intValue());
    }

    @Override
    public List<OnsiteRow> onsite(int offset, int limit) {
        return jdbc.query("""
                        SELECT a.id AS appointment_id, a.appointment_no, v.name AS visitor_name, v.id_card_mask,
                               a.purpose, vr.checkin_at, a.end_time
                        FROM visit_record vr
                        JOIN appointment a ON a.id = vr.appointment_id
                        LEFT JOIN visitor v ON v.id = a.visitor_id
                        WHERE vr.status = 1
                        ORDER BY vr.checkin_at
                        LIMIT ? OFFSET ?
                        """,
                (rs, i) -> new OnsiteRow(
                        rs.getLong("appointment_id"),
                        rs.getString("appointment_no"),
                        rs.getString("visitor_name"),
                        rs.getString("id_card_mask"),
                        rs.getString("purpose"),
                        rs.getTimestamp("checkin_at") == null ? null : rs.getTimestamp("checkin_at").toLocalDateTime(),
                        rs.getTimestamp("end_time") == null ? null : rs.getTimestamp("end_time").toLocalDateTime()),
                limit, offset);
    }

    @Override
    public long countOnsite() {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM visit_record WHERE status = 1", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<PendingRow> pending(int offset, int limit) {
        return jdbc.query("""
                        SELECT a.id AS appointment_id, a.appointment_no, v.name AS visitor_name, v.id_card_mask,
                               a.purpose, a.start_time, a.end_time, a.source, a.created_at
                        FROM appointment a
                        LEFT JOIN visitor v ON v.id = a.visitor_id
                        WHERE a.status IN (20, 25)
                        ORDER BY a.created_at
                        LIMIT ? OFFSET ?
                        """,
                (rs, i) -> new PendingRow(
                        rs.getLong("appointment_id"),
                        rs.getString("appointment_no"),
                        rs.getString("visitor_name"),
                        rs.getString("id_card_mask"),
                        rs.getString("purpose"),
                        rs.getTimestamp("start_time").toLocalDateTime(),
                        rs.getTimestamp("end_time").toLocalDateTime(),
                        rs.getInt("source"),
                        rs.getTimestamp("created_at") == null ? null
                                : rs.getTimestamp("created_at").toLocalDateTime()),
                limit, offset);
    }

    @Override
    public long countPending() {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM appointment WHERE status IN (20, 25)", Long.class);
        return count == null ? 0 : count;
    }
}
