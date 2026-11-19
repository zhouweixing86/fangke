package com.fangke.visitor.admin.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 管理后台查询（预约查询 / 通行记录）。只读，避免污染业务仓储。
 */
public interface AdminQueryRepository {

    record AppointmentRow(Long id, String appointmentNo, String visitorName, String idCardMask,
                          String visitorType, String purpose, LocalDateTime startTime, LocalDateTime endTime,
                          int status, int source, String rejectReason, Long gateId, int gateScope,
                          String appliedGates, String approvedGates, Integer grantStatus,
                          LocalDateTime createdAt) {
    }

    record EventRow(Long id, String eventUuid, String deviceSn, int direction, LocalDateTime eventTime,
                    int identifyType, String personNo, String personName, int resultStatus, int source,
                    int mediaCount) {
    }

    List<AppointmentRow> searchAppointments(String keyword, Integer status, LocalDate from, LocalDate to,
                                            int offset, int limit);

    long countAppointments(String keyword, Integer status, LocalDate from, LocalDate to);

    Optional<AppointmentRow> findAppointment(Long id);

    List<EventRow> searchEvents(LocalDate from, LocalDate to, Integer resultStatus, String personNo,
                                int offset, int limit);

    long countEvents(LocalDate from, LocalDate to, Integer resultStatus, String personNo);
}
