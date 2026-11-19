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
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcApprovalTaskRepository implements ApprovalTaskRepository {

    private static final String SELECT = """
            SELECT id, appointment_id, level, assignee_id, status, reminder_count, last_reminder_at,
                   finished_at, created_at, assignee_type, assignee_role
            FROM approval_task
            """;

    private static final RowMapper<ApprovalTask> MAPPER = new RowMapper<>() {
        @Override
        public ApprovalTask mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ApprovalTask(
                    rs.getLong("id"),
                    rs.getLong("appointment_id"),
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
                    "INSERT INTO approval_task (appointment_id, level, assignee_id, status, "
                            + "assignee_type, assignee_role) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.appointmentId());
            ps.setInt(2, task.level());
            ps.setLong(3, task.assigneeId());
            ps.setInt(4, task.status());
            ps.setInt(5, task.assigneeType());
            ps.setString(6, task.assigneeRole());
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
    public long countPendingByRole(String role) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM approval_task WHERE assignee_role = ? AND status = 1 AND assignee_type = 2",
                Long.class, role);
        return count == null ? 0 : count;
    }

    @Override
    public List<TodoRow> findTodo(Long assigneeId, int offset, int limit) {
        return jdbc.query("""
                        SELECT t.id AS task_id, t.level, a.id AS appointment_id, a.appointment_no,
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
    public List<TodoRow> findTodoByRole(String role, int offset, int limit) {
        return jdbc.query("""
                        SELECT t.id AS task_id, t.level, a.id AS appointment_id, a.appointment_no,
                               v.name AS visitor_name, v.id_card_mask, a.purpose, a.start_time, a.end_time,
                               t.created_at
                        FROM approval_task t
                        JOIN appointment a ON a.id = t.appointment_id
                        LEFT JOIN visitor v ON v.id = a.visitor_id
                        WHERE t.assignee_role = ? AND t.status = 1 AND t.assignee_type = 2
                        ORDER BY t.created_at
                        LIMIT ? OFFSET ?
                        """,
                TASK_ROW_MAPPER, role, limit, offset);
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
}
