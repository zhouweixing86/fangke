package com.fangke.visitor.approval.repository;

import java.time.LocalDateTime;
import java.util.List;

public interface ApprovalActionRepository {

    record ActionRow(Long id, Long taskId, Long operatorId, int operatorType, int action, Long targetUser,
                     String comment, String gateSnapshot, LocalDateTime createdAt) {
    }

    void insert(Long taskId, Long appointmentId, Long operatorId, int action, Long targetUser, String comment);

    /** 双审（C-65）：要记下操作人类型（员工/后台账号）与本次批准的门点。 */
    void insert(Long taskId, Long appointmentId, Long operatorId, int operatorType, int action,
                Long targetUser, String comment, String gateSnapshot);

    List<ActionRow> listByAppointment(Long appointmentId);
}
