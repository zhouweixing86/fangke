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
    public java.util.Map<Long, List<Long>> findGateIds(Collection<Long> appointmentIds, int stage) {
        java.util.Map<Long, List<Long>> view = new java.util.LinkedHashMap<>();
        if (appointmentIds == null || appointmentIds.isEmpty()) {
            return view;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(appointmentIds.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>(appointmentIds);
        args.add(stage);
        jdbc.query("SELECT appointment_id, gate_id FROM appointment_gate "
                        + "WHERE appointment_id IN (" + placeholders + ") AND stage = ? ORDER BY gate_id",
                rs -> {
                    view.computeIfAbsent(rs.getLong(1), k -> new java.util.ArrayList<>()).add(rs.getLong(2));
                }, args.toArray());
        return view;
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
