package com.fangke.visitor.device.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 授权状态查询实现：预约单 + 授权表联查（docs/10 §5、§7）。
 *
 * <p>在线比对是门口等人场景，实际部署时应在此层前面加 Redis/Caffeine 缓存
 * （docs/11 §1.3 要求 P99 &lt; 300ms 且不访问远程服务）。
 */
@Repository
public class JdbcGrantChecker implements GrantChecker {

    /** 预约单可直接通行的状态：35 GRANTED、40 CHECKED_IN。 */
    private static final int STATUS_GRANTED = 35;
    private static final int STATUS_CHECKED_IN = 40;
    /** 授权有效状态。 */
    private static final int GRANT_ACTIVE = 1;

    private static final String SQL = """
            SELECT a.status AS appt_status, g.status AS grant_status,
                   g.valid_from, g.valid_to, g.gate_id
            FROM appointment a
            LEFT JOIN access_grant g ON g.appointment_id = a.id
            WHERE a.appointment_no = ?
            ORDER BY g.id
            """;

    private record Row(int apptStatus, int grantStatus, LocalDateTime validFrom,
                       LocalDateTime validTo, Long gateId) {
    }

    private final JdbcTemplate jdbc;

    public JdbcGrantChecker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public State check(String appointmentNo, Long gateId, Instant now) {
        if (appointmentNo == null || appointmentNo.isBlank()) {
            return State.NO_GRANT;
        }
        List<Row> rows = jdbc.query(SQL, (rs, i) -> new Row(
                rs.getInt("appt_status"),
                rs.getInt("grant_status"),
                toLocal(rs.getTimestamp("valid_from")),
                toLocal(rs.getTimestamp("valid_to")),
                rs.getLong("gate_id")), appointmentNo);

        if (rows.isEmpty()) {
            return State.NO_GRANT;
        }
        Row first = rows.get(0);
        if (first.apptStatus() != STATUS_GRANTED && first.apptStatus() != STATUS_CHECKED_IN) {
            return State.NO_GRANT;
        }
        // 多门点（C-64）：一个预约单有多条授权，任一命中「该门点 + 有效时段」即放行
        List<Row> active = rows.stream().filter(r -> r.grantStatus() == GRANT_ACTIVE).toList();
        if (active.isEmpty()) {
            return State.NO_GRANT;
        }
        List<Row> forGate = gateId == null
                ? active
                : active.stream().filter(r -> r.gateId() == null || gateId.equals(r.gateId())).toList();
        if (forGate.isEmpty()) {
            return State.GATE_MISMATCH;
        }
        for (Row row : forGate) {
            if (inWindow(row, now)) {
                return State.OK;
            }
        }
        return State.OUT_OF_WINDOW;
    }

    private boolean inWindow(Row row, Instant now) {
        if (row.validFrom() != null && now.isBefore(toInstant(row.validFrom()))) {
            return false;
        }
        return row.validTo() == null || !now.isAfter(toInstant(row.validTo()));
    }

    @Override
    public Long resolveGateId(String appointmentNo) {
        if (appointmentNo == null || appointmentNo.isBlank()) {
            return null;
        }
        return jdbc.query("""
                        SELECT g.gate_id FROM access_grant g
                        JOIN appointment a ON a.id = g.appointment_id
                        WHERE a.appointment_no = ?
                        ORDER BY g.id
                        LIMIT 1
                        """,
                (rs, i) -> rs.getLong(1), appointmentNo).stream().findFirst().orElse(null);
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static Instant toInstant(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
