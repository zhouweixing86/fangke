package com.fangke.visitor.approval.model;

import java.time.LocalDateTime;

/**
 * 审批任务（docs/10 §6 approval_task）。
 *
 * <p>双审（C-65）下有两类任务：
 * <ul>
 *   <li>{@link #TYPE_EMPLOYEE}：指派给具体被访员工（一级确认）；</li>
 *   <li>{@link #TYPE_ROLE}：指派给某个后台角色（二级门卫复核），该角色的任一账号都可处理，
 *       避免保安轮班时要指定到人。</li>
 * </ul>
 *
 * @param level        1 一级（被访人确认）/ 2 二级（保安复核）
 * @param assigneeType 1 员工任务 / 2 角色任务
 * @param assigneeRole 角色任务的角色码（如 GUARD），员工任务为 null
 * @param status       1待处理 2已通过 3已驳回 4已跳过 5已转办 6已失效
 */
public record ApprovalTask(
        Long id,
        Long appointmentId,
        int level,
        Long assigneeId,
        int status,
        int reminderCount,
        LocalDateTime lastReminderAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        int assigneeType,
        String assigneeRole) {

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_PASSED = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_SKIPPED = 4;
    public static final int STATUS_TRANSFERRED = 5;
    public static final int STATUS_INVALID = 6;

    public static final int TYPE_EMPLOYEE = 1;
    public static final int TYPE_ROLE = 2;

    /** 一级：指派给被访员工。 */
    public static ApprovalTask employeeTask(Long appointmentId, int level, Long employeeId) {
        return new ApprovalTask(null, appointmentId, level, employeeId, STATUS_PENDING, 0, null, null, null,
                TYPE_EMPLOYEE, null);
    }

    /** 二级：指派给角色（保安复核），不绑定具体人。 */
    public static ApprovalTask roleTask(Long appointmentId, int level, String role) {
        return new ApprovalTask(null, appointmentId, level, 0L, STATUS_PENDING, 0, null, null, null,
                TYPE_ROLE, role);
    }

    public boolean pending() {
        return status == STATUS_PENDING;
    }

    public boolean roleTask() {
        return assigneeType == TYPE_ROLE;
    }

    public ApprovalTask withAssignee(Long newAssignee) {
        return new ApprovalTask(id, appointmentId, level, newAssignee, status, reminderCount,
                lastReminderAt, finishedAt, createdAt, assigneeType, assigneeRole);
    }

    public ApprovalTask withStatus(int newStatus) {
        return new ApprovalTask(id, appointmentId, level, assigneeId, newStatus, reminderCount,
                lastReminderAt, finishedAt, createdAt, assigneeType, assigneeRole);
    }
}
