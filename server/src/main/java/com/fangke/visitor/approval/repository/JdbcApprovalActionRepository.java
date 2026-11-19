package com.fangke.visitor.approval.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

@Repository
public class JdbcApprovalActionRepository implements ApprovalActionRepository {

    private final JdbcTemplate jdbc;

    public JdbcApprovalActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Long taskId, Long appointmentId, Long operatorId, int action,
                       Long targetUser, String comment) {
        insert(taskId, appointmentId, operatorId, 1, action, targetUser, comment, null);
    }

    @Override
    public void insert(Long taskId, Long appointmentId, Long operatorId, int operatorType, int action,
                       Long targetUser, String comment, String gateSnapshot) {
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO approval_action (task_id, appointment_id, operator_id, operator_type, action,
                        target_user, comment, gate_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setLong(1, taskId);
            ps.setLong(2, appointmentId);
            ps.setLong(3, operatorId);
            ps.setInt(4, operatorType);
            ps.setInt(5, action);
            if (targetUser == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, targetUser);
            }
            ps.setString(7, comment);
            ps.setString(8, gateSnapshot);
            return ps;
        });
    }

    @Override
    public List<ActionRow> listByAppointment(Long appointmentId) {
        return jdbc.query("""
                        SELECT id, task_id, operator_id, operator_type, action, target_user, comment,
                               gate_snapshot, created_at
                        FROM approval_action WHERE appointment_id = ? ORDER BY id
                        """,
                (rs, i) -> {
                    long target = rs.getLong("target_user");
                    Long targetUser = rs.wasNull() ? null : target;
                    return new ActionRow(
                            rs.getLong("id"),
                            rs.getLong("task_id"),
                            rs.getLong("operator_id"),
                            rs.getInt("operator_type"),
                            rs.getInt("action"),
                            targetUser,
                            rs.getString("comment"),
                            rs.getString("gate_snapshot"),
                            rs.getTimestamp("created_at").toLocalDateTime());
                },
                appointmentId);
    }
}
