package com.fangke.visitor.guard.repository;

import com.fangke.visitor.config.AppProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcGuardQueryRepository implements GuardQueryRepository {

    /** 车闸相机的心跳（Rtd）间隔比人行终端宽松，给 3 分钟宽限，避免列表一闪一闪地变离线。 */
    private static final int VEHICLE_HEART_GRACE_SECONDS = 180;

    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public JdbcGuardQueryRepository(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public Counts counts() {
        Long onsite = jdbc.queryForObject("SELECT COUNT(1) FROM visit_record WHERE status = 1", Long.class);
        Long pending = jdbc.queryForObject("SELECT COUNT(1) FROM appointment WHERE status IN (20, 25)", Long.class);
        Long today = jdbc.queryForObject("SELECT COUNT(1) FROM visit_record WHERE visit_date = CURDATE()",
                Long.class);
        Integer total = jdbc.queryForObject("SELECT COUNT(1) FROM device WHERE status = 1", Integer.class);
        // 设备断电后 online_state 可能还留着 1，按心跳新鲜度算在线才准（和列表口径一致）
        int heartSeconds = Math.max(5, props.getDevice().getPullHeartSeconds());
        Integer online = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN channel = 'VEHICLE'
                        THEN last_heartbeat >= DATE_SUB(NOW(), INTERVAL ? SECOND)
                        ELSE last_heartbeat >= DATE_SUB(NOW(), INTERVAL ? SECOND) END), 0)
                FROM device WHERE status = 1
                """, Integer.class, VEHICLE_HEART_GRACE_SECONDS, heartSeconds);
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
