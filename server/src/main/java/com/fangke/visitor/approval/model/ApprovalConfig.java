package com.fangke.visitor.approval.model;

import java.util.List;

/**
 * 审批配置（docs/10 §6 approval_config）。
 *
 * @param defaultL1User  被访人手机号匹配不到员工时的默认一级审批人（BR-A-002）
 * @param l2CandidateIds 旧：二级审批候选池（员工 ID），双审口径下不再使用，仅保留兼容
 * @param l2Role         二级复核角色（默认 GUARD=保安），该角色的后台账号都可处理（C-65）
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

    /** 默认口径：被访人确认 + 保安复核（C-65）。 */
    public static ApprovalConfig defaults() {
        return new ApprovalConfig(null, "GLOBAL", 2, null, List.of(), ROLE_GUARD, 30, 2);
    }

    public boolean twoLevel() {
        return approvalLevel >= 2;
    }

    public String roleOrDefault() {
        return l2Role == null || l2Role.isBlank() ? ROLE_GUARD : l2Role;
    }
}
