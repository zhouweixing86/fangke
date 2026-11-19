package com.fangke.visitor.guard.repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保安工作台查询（只读）。
 */
public interface GuardQueryRepository {

    record Counts(int onsite, int pendingApproval, int devicesOnline, int devicesTotal, int todayCheckins) {
    }

    record OnsiteRow(Long appointmentId, String appointmentNo, String visitorName, String idCardMask,
                     String purpose, LocalDateTime checkinAt, LocalDateTime endTime) {
    }

    record PendingRow(Long appointmentId, String appointmentNo, String visitorName, String idCardMask,
                      String purpose, LocalDateTime startTime, LocalDateTime endTime,
                      int source, LocalDateTime createdAt) {
    }

    Counts counts();

    List<OnsiteRow> onsite(int offset, int limit);

    long countOnsite();

    List<PendingRow> pending(int offset, int limit);

    long countPending();
}
