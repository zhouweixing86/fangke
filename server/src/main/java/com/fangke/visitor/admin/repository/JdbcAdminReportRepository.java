package com.fangke.visitor.admin.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcAdminReportRepository implements AdminReportRepository {

    /** 预约“已通过”及之后的状态：30 已通过 / 35 可通行 / 40 已签到 / 50 已签离。 */
    private static final String APPROVED_STATES = "(30,35,40,50)";

    private final JdbcTemplate jdbc;

    public JdbcAdminReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Overview overview(LocalDate from, LocalDate to) {
        Timestamp fromTs = Timestamp.valueOf(from.atStartOfDay());
        Timestamp toTs = Timestamp.valueOf(to.plusDays(1).atStartOfDay());

        long appointments = count("SELECT COUNT(1) FROM appointment "
                + "WHERE start_time >= ? AND start_time < ?", fromTs, toTs);
        long approved = count("SELECT COUNT(1) FROM appointment "
                + "WHERE start_time >= ? AND start_time < ? AND status IN " + APPROVED_STATES, fromTs, toTs);
        long rejected = count("SELECT COUNT(1) FROM appointment "
                + "WHERE start_time >= ? AND start_time < ? AND status = 60", fromTs, toTs);
        long pending = count("SELECT COUNT(1) FROM appointment "
                + "WHERE start_time >= ? AND start_time < ? AND status IN (20,25)", fromTs, toTs);
        long withdrawn = count("SELECT COUNT(1) FROM appointment "
                + "WHERE start_time >= ? AND start_time < ? AND status IN (80,90)", fromTs, toTs);

        long checkins = count("SELECT COUNT(1) FROM visit_record "
                + "WHERE checkin_at >= ? AND checkin_at < ?", fromTs, toTs);
        long onsiteNow = count("SELECT COUNT(1) FROM visit_record WHERE status = 1");

        long events = count("SELECT COUNT(1) FROM access_event "
                + "WHERE event_time >= ? AND event_time < ?", fromTs, toTs);
        long eventPass = count("SELECT COUNT(1) FROM access_event "
                + "WHERE event_time >= ? AND event_time < ? AND result_status = 1", fromTs, toTs);
        long eventFail = events - eventPass;

        Double avgMinutes = jdbc.queryForObject("SELECT AVG(TIMESTAMPDIFF(SECOND, created_at, finished_at)) / 60 "
                        + "FROM approval_task WHERE finished_at >= ? AND finished_at < ?",
                Double.class, fromTs, toTs);

        return new Overview(appointments, approved, rejected, pending, withdrawn,
                checkins, onsiteNow, events, eventPass, eventFail, avgMinutes);
    }

    @Override
    public List<Daily> daily(LocalDate from, LocalDate to) {
        Timestamp fromTs = Timestamp.valueOf(from.atStartOfDay());
        Timestamp toTs = Timestamp.valueOf(to.plusDays(1).atStartOfDay());

        Map<LocalDate, Long> appointments = groupByDay("SELECT DATE(start_time) d, COUNT(1) c FROM appointment "
                + "WHERE start_time >= ? AND start_time < ? GROUP BY DATE(start_time)", fromTs, toTs);
        Map<LocalDate, Long> checkins = groupByDay("SELECT DATE(checkin_at) d, COUNT(1) c FROM visit_record "
                + "WHERE checkin_at >= ? AND checkin_at < ? GROUP BY DATE(checkin_at)", fromTs, toTs);

        Map<LocalDate, long[]> events = new HashMap<>();
        jdbc.query("SELECT DATE(event_time) d, result_status s, COUNT(1) c FROM access_event "
                        + "WHERE event_time >= ? AND event_time < ? GROUP BY DATE(event_time), result_status",
                rs -> {
                    LocalDate day = rs.getDate("d").toLocalDate();
                    long[] pair = events.computeIfAbsent(day, k -> new long[2]);
                    if (rs.getInt("s") == 1) {
                        pair[0] += rs.getLong("c");
                    } else {
                        pair[1] += rs.getLong("c");
                    }
                }, fromTs, toTs);

        List<Daily> list = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            long[] pair = events.getOrDefault(day, new long[2]);
            list.add(new Daily(day,
                    appointments.getOrDefault(day, 0L),
                    checkins.getOrDefault(day, 0L),
                    pair[0],
                    pair[1]));
        }
        return list;
    }

    @Override
    public List<VisitorStat> visitorByDept(LocalDate from, LocalDate to) {
        Timestamp fromTs = Timestamp.valueOf(from.atStartOfDay());
        Timestamp toTs = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        return visitorStats("""
                SELECT COALESCE(d.dept_name, '未指定部门') AS k,
                       COUNT(1) AS total,
                       SUM(CASE WHEN a.status IN (30,35,40,50) THEN 1 ELSE 0 END) AS approved,
                       SUM(CASE WHEN v.c IS NULL THEN 0 ELSE 1 END) AS arrived
                FROM appointment a
                LEFT JOIN employee e ON e.id = a.visit_employee_id
                LEFT JOIN dept d ON d.id = e.dept_id
                LEFT JOIN (SELECT appointment_id, COUNT(1) AS c FROM visit_record
                           WHERE checkin_at >= ? AND checkin_at < ? GROUP BY appointment_id) v
                       ON v.appointment_id = a.id
                WHERE a.start_time >= ? AND a.start_time < ?
                GROUP BY d.dept_name
                ORDER BY total DESC
                """, fromTs, toTs, fromTs, toTs);
    }

    @Override
    public List<VisitorStat> visitorByType(LocalDate from, LocalDate to) {
        Timestamp fromTs = Timestamp.valueOf(from.atStartOfDay());
        Timestamp toTs = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        return visitorStats("""
                SELECT COALESCE(NULLIF(a.visitor_type, ''), '未填写') AS k,
                       COUNT(1) AS total,
                       SUM(CASE WHEN a.status IN (30,35,40,50) THEN 1 ELSE 0 END) AS approved,
                       SUM(CASE WHEN v.c IS NULL THEN 0 ELSE 1 END) AS arrived
                FROM appointment a
                LEFT JOIN (SELECT appointment_id, COUNT(1) AS c FROM visit_record
                           WHERE checkin_at >= ? AND checkin_at < ? GROUP BY appointment_id) v
                       ON v.appointment_id = a.id
                WHERE a.start_time >= ? AND a.start_time < ?
                GROUP BY a.visitor_type
                ORDER BY total DESC
                """, fromTs, toTs, fromTs, toTs);
    }

    @Override
    public List<AccessStat> accessByDevice(LocalDate from, LocalDate to) {
        Timestamp fromTs = Timestamp.valueOf(from.atStartOfDay());
        Timestamp toTs = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        return accessStats("""
                SELECT COALESCE(NULLIF(device_sn, ''), '未知设备') AS k,
                       COUNT(1) AS total,
                       SUM(CASE WHEN result_status = 1 THEN 1 ELSE 0 END) AS pass,
                       SUM(CASE WHEN result_status = 1 THEN 0 ELSE 1 END) AS fail
                FROM access_event
                WHERE event_time >= ? AND event_time < ?
                GROUP BY device_sn
                ORDER BY total DESC
                """, fromTs, toTs);
    }

    @Override
    public List<AccessStat> accessByIdentifyType(LocalDate from, LocalDate to) {
        Timestamp fromTs = Timestamp.valueOf(from.atStartOfDay());
        Timestamp toTs = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        return accessStats("""
                SELECT CAST(identify_type AS CHAR) AS k,
                       COUNT(1) AS total,
                       SUM(CASE WHEN result_status = 1 THEN 1 ELSE 0 END) AS pass,
                       SUM(CASE WHEN result_status = 1 THEN 0 ELSE 1 END) AS fail
                FROM access_event
                WHERE event_time >= ? AND event_time < ?
                GROUP BY identify_type
                ORDER BY total DESC
                """, fromTs, toTs);
    }

    private List<VisitorStat> visitorStats(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> new VisitorStat(
                rs.getString("k"), rs.getLong("total"),
                rs.getLong("approved"), rs.getLong("arrived")), args);
    }

    private List<AccessStat> accessStats(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> new AccessStat(
                rs.getString("k"), rs.getLong("total"),
                rs.getLong("pass"), rs.getLong("fail")), args);
    }

    private Map<LocalDate, Long> groupByDay(String sql, Timestamp from, Timestamp to) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            Date day = rs.getDate("d");
            if (day != null) {
                result.put(day.toLocalDate(), rs.getLong("c"));
            }
        }, from, to);
        return result;
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }
}
