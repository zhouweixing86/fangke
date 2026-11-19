package com.fangke.visitor.appointment.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存门点集合仓储（C-64）。
 */
public class InMemoryAppointmentGateRepository implements AppointmentGateRepository {

    private final Map<String, List<Long>> store = new ConcurrentHashMap<>();

    private String key(Long appointmentId, int stage) {
        return appointmentId + ":" + stage;
    }

    @Override
    public List<Long> findGateIds(Long appointmentId, int stage) {
        if (appointmentId == null) {
            return List.of();
        }
        return new ArrayList<>(store.getOrDefault(key(appointmentId, stage), List.of()));
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
        store.put(key(appointmentId, stage), List.copyOf(new LinkedHashSet<>(gateIds)));
    }

    @Override
    public void clearStage(Long appointmentId, int stage) {
        store.remove(key(appointmentId, stage));
    }
}
