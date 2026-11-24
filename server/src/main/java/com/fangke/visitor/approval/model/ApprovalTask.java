package com.fangke.visitor.approval.model;

import java.time.LocalDateTime;

/**
 * 审批任务（docs/10 §6 approval_task）。
 *
 * <p>三种任务类型：
 * <ul>
 *   <li>{@link #TYPE_EMPLOYEE}：指派给具体员工（历史双审的一级确认，已不再创建）</li>
 *   <li>{@link #TYPE_ROLE}：指派给某个后台角色，该角色任一账号都可处理（单签/或签节点）</li>
 *   <li>{@link #TYPE_ADMIN_USER}：指派到具体后台账号（会签节点：该角色每个账号一条）</li>
 * </ul>
 *
 * @param level    节点序号（与 {@code approval_node.seq} 一致；历史数据就是审批级数）
 * @param nodeId   节点 ID（V24 之前的历史任务为 null）
 * @param nodeName 节点名称快照，列表直接显示，避免再 join
 */
public record ApprovalTask(
        Long id,
        Long appointmentId,
        Long templateId,
        Long nodeId,
        String nodeName,
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
    public static final int TYPE_ADMIN_USER = 3;

    /** 历史工厂：员工任务（已不再创建，保留兼容）。 */
    public static ApprovalTask employeeTask(Long appointmentId, int level, Long employeeId) {
        return new ApprovalTask(null, appointmentId, null, null, null, level, employeeId,
                STATUS_PENDING, 0, null, null, null, TYPE_EMPLOYEE, null);
    }

    /** 角色任务：单签/或签节点，该角色任一账号可处理。 */
    public static ApprovalTask roleTask(Long appointmentId, int seq, String role,
                                        Long templateId, Long nodeId, String nodeName) {
        return new ApprovalTask(null, appointmentId, templateId, nodeId, nodeName, seq, 0L,
                STATUS_PENDING, 0, null, null, null, TYPE_ROLE, role);
    }

    /** 账号任务：会签节点，该角色当前每个启用账号一条。 */
    public static ApprovalTask adminUserTask(Long appointmentId, int seq, Long adminUserId, String role,
                                             Long templateId, Long nodeId, String nodeName) {
        return new ApprovalTask(null, appointmentId, templateId, nodeId, nodeName, seq, adminUserId,
                STATUS_PENDING, 0, null, null, null, TYPE_ADMIN_USER, role);
    }

    public boolean pending() {
        return status == STATUS_PENDING;
    }

    public boolean roleTask() {
        return assigneeType == TYPE_ROLE;
    }

    public boolean adminUserTask() {
        return assigneeType == TYPE_ADMIN_USER;
    }

    /** 角色任务或账号任务都属于"后台审批"，走 /api/guard/approval/**。 */
    public boolean adminApprovalTask() {
        return roleTask() || adminUserTask();
    }

    public ApprovalTask withAssignee(Long newAssignee) {
        return new ApprovalTask(id, appointmentId, templateId, nodeId, nodeName, level, newAssignee,
                status, reminderCount, lastReminderAt, finishedAt, createdAt, assigneeType, assigneeRole);
    }

    public ApprovalTask withStatus(int newStatus) {
        return new ApprovalTask(id, appointmentId, templateId, nodeId, nodeName, level, assigneeId,
                newStatus, reminderCount, lastReminderAt, finishedAt, createdAt, assigneeType, assigneeRole);
    }
}
