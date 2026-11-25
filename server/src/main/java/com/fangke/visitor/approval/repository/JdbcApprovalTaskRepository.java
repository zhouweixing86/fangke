package com.fangke.visitor.approval.repository;

import com.fangke.visitor.approval.model.ApprovalTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcApprovalTaskRepository implements ApprovalTaskRepository {

    private static final String SELECT = """
            SELECT id, appointment_id, template_id, node_id, node_name, level, assignee_id, status,
                   reminder_count, last_reminder_at, finished_at, created_at, assignee_type, assignee_role
            FROM approval_task
            """;

    private static final RowMapper<ApprovalTask> MAPPER = new RowMapper<>() {
        @Override
        public ApprovalTask mapRow(ResultSet rs, int rowNum) throws SQLException {
            long templateId = rs.getLong("template_id");
            Long template = rs.wasNull() ? null : templateId;
            long nodeId = rs.getLong("node_id");
            Long node = rs.wasNull() ? null : nodeId;
            return new ApprovalTask(
                    rs.getLong("id"),
                    rs.getLong("appointment_id"),
                    template,
                    node,
                    rs.getString("node_name"),
                    rs.getInt("level"),
                    rs.getLong("assignee_id"),
                    rs.getInt("status"),
                    rs.getInt("reminder_count"),
                    rs.getTimestamp("last_reminder_at") == null ? null
                            : rs.getTimestamp("last_reminder_at").toLocalDateTime(),
                    rs.getTimestamp("finished_at") == null ? null
                            : rs.getTimestamp("finished_at").toLocalDateTime(),
                    rs.getTimestamp("created_at") == null ? null
                            : rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getInt("assignee_type"),
                    rs.getString("assignee_role"));
        }
    };

    private final JdbcTemplate jdbc;

    private static final RowMapper<ApprovalTaskRepository.TodoRow> TASK_ROW_MAPPER = (rs, i) ->
            new ApprovalTaskRepository.TodoRow(
                    rs.getLong("task_id"),
                    rs.getInt("level"),
                    rs.getString("node_name"),
                    rs.getLong("appointment_id"),
                    rs.getString("appointment_no"),
                    rs.getString("visitor_name"),
                    rs.getString("id_card_mask"),
                    rs.getString("purpose"),
                    rs.getTimestamp("start_time").toLocalDateTime(),
                    rs.getTimestamp("end_time").toLocalDateTime(),
                    rs.getTimestamp("created_at").toLocalDateTime());

    public JdbcApprovalTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(ApprovalTask task) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO approval_task (appointment_id, template_id, node_id, node_name, level, "
                            + "assignee_id, status, assignee_type, assignee_role) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.appointmentId());
            setNullableLong(ps, 2, task.templateId());
            setNullableLong(ps, 3, task.nodeId());
            ps.setString(4, task.nodeName());
            ps.setInt(5, task.level());
            ps.setLong(6, task.assigneeId());
            ps.setInt(7, task.status());
            ps.setInt(8, task.assigneeType());
            ps.setString(9, task.assigneeRole());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("审批任务写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public Optional<ApprovalTask> findById(Long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<ApprovalTask> findPendingByAppointment(Long appointmentId) {
        return jdbc.query(SELECT + " WHERE appointment_id = ? AND status = 1 ORDER BY level, id",
                MAPPER, appointmentId);
    }

    @Override
    public Optional<ApprovalTask> findPendingByAppointmentAndLevel(Long appointmentId, int level) {
        return jdbc.query(SELECT + " WHERE appointment_id = ? AND level = ? AND status = 1 ORDER BY id LIMIT 1",
                MAPPER, appointmentId, level).stream().findFirst();
    }

    @Override
    public long countPending(Long assigneeId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM approval_task WHERE assignee_id = ? AND status = 1 AND assignee_type = 1",
                Long.class, assigneeId);
        return count == null ? 0 : count;
    }

    @Override
    public long countPendingByRoles(List<String> roles, Long adminUserId) {
        List<String> safeRoles = roles == null ? List.of() : roles;
        String placeholders = safeRoles.isEmpty() ? "NULL"
                : String.join(",", java.util.Collections.nCopies(safeRoles.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(adminUserId == null ? -1L : adminUserId);
        args.addAll(safeRoles);
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(1) FROM approval_task
                        WHERE status = 1 AND (
                            (assignee_type = 3 AND assignee_id = ?)
                            OR (assignee_type = 2 AND assignee_role IN (%s))
                        )
                        """.formatted(placeholders),
                Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<TodoRow> findTodo(Long assigneeId, int offset, int limit) {
        return jdbc.query("""
                        SELECT t.id AS task_id, t.level, t.node_name, a.id AS appointment_id, a.appointment_no,
                               v.name AS visitor_name, v.id_card_mask, a.purpose, a.start_time, a.end_time,
                               t.created_at
                        FROM approval_task t
                        JOIN appointment a ON a.id = t.appointment_id
                        LEFT JOIN visitor v ON v.id = a.visitor_id
                        WHERE t.assignee_id = ? AND t.status = 1 AND t.assignee_type = 1
                        ORDER BY t.created_at
                        LIMIT ? OFFSET ?
                        """,
                TASK_ROW_MAPPER, assigneeId, limit, offset);
    }

    @Override
    public List<TodoRow> findTodoByRoles(List<String> roles, Long adminUserId, int offset, int limit) {
        List<String> safeRoles = roles == null ? List.of() : roles;
        String placeholders = safeRoles.isEmpty() ? "NULL"
                : String.join(",", java.util.Collections.nCopies(safeRoles.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(adminUserId == null ? -1L : adminUserId);
        args.addAll(safeRoles);
        args.add(limit);
        args.add(offset);
        return jdbc.query("""
                        SELECT t.id AS task_id, t.level, t.node_name, a.id AS appointment_id, a.appointment_no,
                               v.name AS visitor_name, v.id_card_mask, a.purpose, a.start_time, a.end_time,
                               t.created_at
                        FROM approval_task t
                        JOIN appointment a ON a.id = t.appointment_id
                        LEFT JOIN visitor v ON v.id = a.visitor_id
                        WHERE t.status = 1 AND (
                            (t.assignee_type = 3 AND t.assignee_id = ?)
                            OR (t.assignee_type = 2 AND t.assignee_role IN (%s))
                        )
                        ORDER BY t.created_at
                        LIMIT ? OFFSET ?
                        """.formatted(placeholders),
                TASK_ROW_MAPPER, args.toArray());
    }

    @Override
    public void updateStatus(Long id, int status) {
        jdbc.update("UPDATE approval_task SET status = ?, finished_at = NOW(3) WHERE id = ?", status, id);
    }

    @Override
    public void updateAssignee(Long id, Long assigneeId) {
        jdbc.update("UPDATE approval_task SET assignee_id = ? WHERE id = ?", assigneeId, id);
    }

    @Override
    public void markReminder(Long id) {
        jdbc.update("UPDATE approval_task SET reminder_count = reminder_count + 1, last_reminder_at = NOW(3) "
                + "WHERE id = ?", id);
    }

    @Override
    public void invalidatePending(Long appointmentId) {
        jdbc.update("UPDATE approval_task SET status = 6, finished_at = NOW(3) "
                + "WHERE appointment_id = ? AND status = 1", appointmentId);
    }

    @Override
    public List<Long> findStalePending(LocalDateTime createdBefore, LocalDateTime lastRemindBefore,
                                       int limit) {
        return jdbc.query("""
                        SELECT id FROM approval_task
                        WHERE status = 1
                          AND created_at < ?
                          AND (last_reminder_at IS NULL OR last_reminder_at < ?)
                        ORDER BY created_at
                        LIMIT ?
                        """,
                (rs, i) -> rs.getLong(1),
                Timestamp.valueOf(createdBefore), Timestamp.valueOf(lastRemindBefore), limit);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    @Override
    public List<DoneRow> findDoneByOperator(long operatorId, int operatorType, Integer action,
                                            java.time.LocalDate from, java.time.LocalDate to,
                                            int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = doneWhere(operatorId, operatorType, action, from, to, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("""
                        SELECT t.id AS task_id, t.level, t.node_name, a.id AS appointment_id, a.appointment_no,
                               v.name AS visitor_name, a.purpose, aa.action, aa.comment, aa.created_at
                        FROM approval_action aa
                        JOIN approval_task t ON t.id = aa.task_id
                        JOIN appointment a ON a.id = aa.appointment_id
                        LEFT JOIN visitor v ON v.id = a.visitor_id
                        """ + where + " ORDER BY aa.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new DoneRow(
                        rs.getLong("task_id"),
                        rs.getInt("level"),
                        rs.getString("node_name"),
                        rs.getLong("appointment_id"),
                        rs.getString("appointment_no"),
                        rs.getString("visitor_name"),
                        rs.getString("purpose"),
                        rs.getInt("action"),
                        rs.getString("comment"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                args.toArray());
    }

    @Override
    public long countDoneByOperator(long operatorId, int operatorType, Integer action,
                                    java.time.LocalDate from, java.time.LocalDate to) {
        List<Object> args = new ArrayList<>();
        String where = doneWhere(operatorId, operatorType, action, from, to, args);
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM approval_action aa" + where,
                Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public DoneStats doneStats(long operatorId, int operatorType, java.time.LocalDate from,
                               java.time.LocalDate to) {
        List<Object> args = new ArrayList<>();
        String where = doneWhere(operatorId, operatorType, null, from, to, args);
        return jdbc.queryForObject("""
                        SELECT COUNT(1) AS total,
                               SUM(CASE WHEN aa.action = 1 THEN 1 ELSE 0 END) AS approved,
                               SUM(CASE WHEN aa.action = 2 THEN 1 ELSE 0 END) AS rejected
                        FROM approval_action aa
                        """ + where,
                (rs, i) -> new DoneStats(rs.getLong("total"), rs.getLong("approved"), rs.getLong("rejected")),
                args.toArray());
    }

    private String doneWhere(long operatorId, int operatorType, Integer action,
                             java.time.LocalDate from, java.time.LocalDate to, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE aa.operator_id = ? AND aa.operator_type = ?");
        args.add(operatorId);
        args.add(operatorType);
        if (action != null) {
            sb.append(" AND aa.action = ?");
            args.add(action);
        }
        if (from != null) {
            sb.append(" AND aa.created_at >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sb.append(" AND aa.created_at < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        return sb.toString();
    }
}
