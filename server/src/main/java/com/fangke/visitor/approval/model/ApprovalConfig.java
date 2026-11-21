package com.fangke.visitor.approval.model;

import java.util.List;

/**
 * 审批配置（docs/10 §6 approval_config）。
 *
 * <p><b>2026-11-21 起为单级审批</b>：预约由登录管理端的人审核（{@code l2Role} 指定的角色，
 * 默认 GUARD=保安/管理员）。员工不再登录审批，{@code defaultL1User} 与 {@code l2CandidateIds}
 * 仅保留字段兼容历史数据。
 *
 * @param l2Role 审核角色（默认 GUARD），该角色的后台账号都可处理
 */
public record ApprovalConfig(
        Long id,
        String scope,
        int approvalLevel,
        Long defaultL1User,
        List<Long> l2CandidateIds,
        String l2Role,
        int reminderIntervalMin,
        int reminderMaxPerTask) {

    public static final String ROLE_GUARD = "GUARD";

    /** 默认口径：单级，管理端角色审核。 */
    public static ApprovalConfig defaults() {
        return new ApprovalConfig(null, "GLOBAL", 1, null, List.of(), ROLE_GUARD, 30, 2);
    }

    /** 是否双级：已废弃（固定单级），保留方法避免调用方报错。 */
    public boolean twoLevel() {
        return approvalLevel >= 2;
    }

    public String roleOrDefault() {
        return l2Role == null || l2Role.isBlank() ? ROLE_GUARD : l2Role;
    }
}
