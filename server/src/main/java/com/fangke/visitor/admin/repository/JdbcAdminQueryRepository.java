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
                   a.area_id, ad.name AS area_name,
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
            LEFT JOIN visit_dict ad ON ad.id = a.area_id AND ad.dict_type = 'AREA'
            """;

    private static final RowMapper<AppointmentRow> APPOINTMENT_MAPPER = (rs, i) -> {
        Integer grantStatus = (Integer) rs.getObject("grant_status");
        long gateId = rs.getLong("gate_id");
        Long gateIdOrNull = rs.wasNull() ? null : gateId;
        long areaId = rs.getLong("area_id");
        Long areaIdOrNull = rs.wasNull() ? null : areaId;
        return new AppointmentRow(
                rs.getLong("id"),
                rs.getString("appointment_no"),
                rs.getString("visitor_name"),
                rs.getString("id_card_mask"),
                rs.getString("visitor_type"),
                rs.getString("purpose"),
                areaIdOrNull,
                rs.getString("area_name"),
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
    public List<AppointmentRow> searchAppointments(String keyword, Integer status, Long areaId,
                                                   LocalDate from, LocalDate to, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = appointmentWhere(keyword, status, areaId, from, to, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(APPOINTMENT_SELECT + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                APPOINTMENT_MAPPER, args.toArray());
    }

    @Override
    public long countAppointments(String keyword, Integer status, Long areaId, LocalDate from, LocalDate to) {
        List<Object> args = new ArrayList<>();
        String where = appointmentWhere(keyword, status, areaId, from, to, args);
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
                               e.fail_reason, e.id_card_mask,
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
                        rs.getString("fail_reason"),
                        rs.getString("id_card_mask"),
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

    /* ---------------- 审批台账（C-80） ---------------- */

    private static final String APPROVAL_RECORD_SELECT = """
            SELECT aa.id, a.appointment_no, v.name AS visitor_name, t.node_name,
                   COALESCE(aa.operator_name, u.real_name, u.username, CONCAT('微信#', aa.operator_id)) AS operator_name,
                   aa.operator_type, aa.action, aa.comment, aa.gate_snapshot, aa.created_at
            FROM approval_action aa
            JOIN appointment a ON a.id = aa.appointment_id
            LEFT JOIN visitor v ON v.id = a.visitor_id
            LEFT JOIN approval_task t ON t.id = aa.task_id
            LEFT JOIN sys_user u ON u.id = aa.operator_id AND aa.operator_type <> 4
            """;

    @Override
    public List<ApprovalRecordRow> searchApprovalRecords(LocalDate from, LocalDate to, Integer action,
                                                         String operator, String keyword,
                                                         int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = approvalRecordWhere(from, to, action, operator, keyword, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(APPROVAL_RECORD_SELECT + where
                        + " ORDER BY aa.created_at DESC, aa.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new ApprovalRecordRow(
                        rs.getLong("id"),
                        rs.getString("appointment_no"),
                        rs.getString("visitor_name"),
                        rs.getString("node_name"),
                        rs.getString("operator_name"),
                        rs.getInt("operator_type"),
                        rs.getInt("action"),
                        rs.getString("comment"),
                        rs.getString("gate_snapshot"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                args.toArray());
    }

    @Override
    public long countApprovalRecords(LocalDate from, LocalDate to, Integer action,
                                     String operator, String keyword) {
        List<Object> args = new ArrayList<>();
        String where = approvalRecordWhere(from, to, action, operator, keyword, args);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM approval_action aa
                JOIN appointment a ON a.id = aa.appointment_id
                LEFT JOIN visitor v ON v.id = a.visitor_id
                LEFT JOIN sys_user u ON u.id = aa.operator_id AND aa.operator_type <> 4
                """ + where, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private String approvalRecordWhere(LocalDate from, LocalDate to, Integer action,
                                       String operator, String keyword, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (from != null) {
            sb.append(" AND aa.created_at >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sb.append(" AND aa.created_at < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        if (action != null) {
            sb.append(" AND aa.action = ?");
            args.add(action);
        }
        if (StringUtils.hasText(operator)) {
            sb.append(" AND COALESCE(aa.operator_name, u.real_name, u.username) LIKE ?");
            args.add("%" + operator.trim() + "%");
        }
        if (StringUtils.hasText(keyword)) {
            sb.append(" AND (a.appointment_no LIKE ? OR v.name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        return sb.toString();
    }

    /* ---------------- 条件拼装 ---------------- */

    private String appointmentWhere(String keyword, Integer status, Long areaId, LocalDate from, LocalDate to,
                                    List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (status != null) {
            sb.append(" AND a.status = ?");
            args.add(status);
        }
        if (areaId != null) {
            sb.append(" AND a.area_id = ?");
            args.add(areaId);
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
            sb.append(" AND (e.person_no LIKE ? OR e.person_name LIKE ? OR e.id_card_mask LIKE ?)");
            String like = "%" + personNo.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return sb.toString();
    }
}
