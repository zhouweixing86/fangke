package com.fangke.visitor.appointment.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public class JdbcAppointmentGateRepository implements AppointmentGateRepository {

    private final JdbcTemplate jdbc;

    public JdbcAppointmentGateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Long> findGateIds(Long appointmentId, int stage) {
        if (appointmentId == null) {
            return List.of();
        }
        return jdbc.query("SELECT gate_id FROM appointment_gate "
                        + "WHERE appointment_id = ? AND stage = ? ORDER BY gate_id",
                (rs, i) -> rs.getLong(1), appointmentId, stage);
    }

    @Override
    public void replaceStage(Long appointmentId, int stage, Collection<Long> gateIds) {
        if (appointmentId == null) {
            return;
        }
        clearStage(appointmentId, stage);
        if (gateIds == null || gateIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT IGNORE INTO appointment_gate (appointment_id, gate_id, stage) VALUES (?, ?, ?)",
                gateIds.stream()
                        .filter(id -> id != null)
                        .distinct()
                        .map(id -> new Object[]{appointmentId, id, stage})
                        .toList());
    }

    @Override
    public void clearStage(Long appointmentId, int stage) {
        jdbc.update("DELETE FROM appointment_gate WHERE appointment_id = ? AND stage = ?", appointmentId, stage);
    }
}
