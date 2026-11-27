package com.fangke.visitor.approval.repository;

import com.fangke.visitor.approval.model.FlowNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAppointmentFlowRepository implements AppointmentFlowRepository {

    private static final RowMapper<FlowNode> MAPPER = (rs, i) -> new FlowNode(
            rs.getInt("seq"),
            rs.getString("node_name"),
            rs.getString("assignee_role"),
            rs.getInt("allow_edit_gates") == 1,
            FlowNode.splitRoles(rs.getString("cc_roles")));

    private final JdbcTemplate jdbc;

    public JdbcAppointmentFlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveAll(Long appointmentId, List<FlowNode> nodes) {
        jdbc.update("DELETE FROM appointment_flow WHERE appointment_id = ?", appointmentId);
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        for (FlowNode node : nodes) {
            jdbc.update("""
                            INSERT INTO appointment_flow (appointment_id, seq, node_name, assignee_role,
                                allow_edit_gates, cc_roles)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    appointmentId, node.seq(), node.nodeName(), node.assigneeRole(),
                    node.allowEditGates() ? 1 : 0, node.ccRolesText());
        }
    }

    @Override
    public List<FlowNode> findByAppointment(Long appointmentId) {
        return jdbc.query("SELECT * FROM appointment_flow WHERE appointment_id = ? ORDER BY seq",
                MAPPER, appointmentId);
    }

    @Override
    public Optional<FlowNode> find(Long appointmentId, int seq) {
        return jdbc.query("SELECT * FROM appointment_flow WHERE appointment_id = ? AND seq = ?",
                MAPPER, appointmentId, seq).stream().findFirst();
    }

    @Override
    public Optional<FlowNode> next(Long appointmentId, int seq) {
        return jdbc.query("SELECT * FROM appointment_flow WHERE appointment_id = ? AND seq > ? ORDER BY seq LIMIT 1",
                MAPPER, appointmentId, seq).stream().findFirst();
    }
}
