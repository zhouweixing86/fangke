package com.fangke.visitor.device.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 到访记录仓储（docs/10 §8 visit_record）。
 */
public interface VisitRecordRepository {

    /** 按人员编号反查其有效授权对应的预约单。 */
    Optional<Long> findAppointmentIdByPersonNo(String personNo);

    /** 未签离的到访记录（即"在访"）。 */
    Optional<Long> findOpenRecordId(Long appointmentId);

    /** 该预约单是否已有任何到访记录。 */
    Optional<Long> findAnyRecordId(Long appointmentId);

    long insertCheckin(Long appointmentId, Long visitorId, Long gateId,
                       LocalDateTime checkinAt, Long checkinEvent, LocalDate visitDate);

    void updateCheckout(long recordId, LocalDateTime checkoutAt, Long checkoutEvent,
                        int checkoutType, int status);

    void updateAppointmentStatus(Long appointmentId, int status);

    record OverdueRecord(Long recordId, Long appointmentId) {
    }

    /** 仍在访但预约结束时间已过的记录（定时任务自动签离用）。 */
    java.util.List<OverdueRecord> findOverdueOpen(LocalDateTime before, int limit);
}
