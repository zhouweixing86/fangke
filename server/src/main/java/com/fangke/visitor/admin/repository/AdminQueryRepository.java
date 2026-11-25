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
                          String visitorType, String purpose, Long areaId, String areaName,
                          LocalDateTime startTime, LocalDateTime endTime,
                          int status, int source, String rejectReason, Long gateId, int gateScope,
                          String appliedGates, String approvedGates, Integer grantStatus,
                          LocalDateTime createdAt) {
    }

    record EventRow(Long id, String eventUuid, String deviceSn, int direction, LocalDateTime eventTime,
                    int identifyType, String personNo, String personName, int resultStatus, int source,
                    String failReason, String idCardMask, int mediaCount) {
    }

    List<AppointmentRow> searchAppointments(String keyword, Integer status, Long areaId,
                                            LocalDate from, LocalDate to, int offset, int limit);

    long countAppointments(String keyword, Integer status, Long areaId, LocalDate from, LocalDate to);

    Optional<AppointmentRow> findAppointment(Long id);

    List<EventRow> searchEvents(LocalDate from, LocalDate to, Integer resultStatus, String personNo,
                                int offset, int limit);

    long countEvents(LocalDate from, LocalDate to, Integer resultStatus, String personNo);

    /** 审批台账行（C-80）：一条审批动作，含单号、访客、节点、审批人、结果、意见、门点快照。 */
    record ApprovalRecordRow(Long id, String appointmentNo, String visitorName, String nodeName,
                             String operatorName, int operatorType, int action, String comment,
                             String gateSnapshot, LocalDateTime createdAt) {
    }

    List<ApprovalRecordRow> searchApprovalRecords(LocalDate from, LocalDate to, Integer action,
                                                  String operator, String keyword,
                                                  int offset, int limit);

    long countApprovalRecords(LocalDate from, LocalDate to, Integer action,
                              String operator, String keyword);
}
