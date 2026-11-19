package com.fangke.visitor.approval.repository;

import com.fangke.visitor.approval.model.ApprovalTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApprovalTaskRepository {

    /** 待办列表行（已 join 预约单与访客，避免前端再查）。 */
    record TodoRow(
            Long taskId,
            int level,
            Long appointmentId,
            String appointmentNo,
            String visitorName,
            String idCardMask,
            String purpose,
            LocalDateTime startTime,
            LocalDateTime endTime,
            LocalDateTime createdAt) {
    }

    long insert(ApprovalTask task);

    Optional<ApprovalTask> findById(Long id);

    List<ApprovalTask> findPendingByAppointment(Long appointmentId);

    Optional<ApprovalTask> findPendingByAppointmentAndLevel(Long appointmentId, int level);

    long countPending(Long assigneeId);

    /** 角色任务待办数（二级保安复核，C-65）。 */
    long countPendingByRole(String role);

    List<TodoRow> findTodo(Long assigneeId, int offset, int limit);

    /** 角色任务待办列表：该角色的任一账号都能看到同一批待复核。 */
    List<TodoRow> findTodoByRole(String role, int offset, int limit);

    void updateStatus(Long id, int status);

    void updateAssignee(Long id, Long assigneeId);

    void markReminder(Long id);

    /** 预约单撤回/驳回时，把其余待处理任务置为已失效。 */
    void invalidatePending(Long appointmentId);

    /**
     * 超时未处理的待办（created_at 早于 createdBefore，且从未催办或上次催办早于 lastRemindBefore）。
     * 定时催办任务用。
     */
    List<Long> findStalePending(LocalDateTime createdBefore, LocalDateTime lastRemindBefore, int limit);
}
