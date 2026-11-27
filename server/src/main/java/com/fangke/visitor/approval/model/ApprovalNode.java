package com.fangke.visitor.approval.model;

/**
 * 审批节点（docs/10 §6 approval_node，V24）。
 *
 * <p>节点**串行**执行：seq 从小到大，一个节点办结后才建下一个节点的任务。
 * 同一节点内按 {@link #approveMode()} 决定多人怎么处理：
 * <ul>
 *   <li>{@link #MODE_SINGLE} / {@link #MODE_ANY}：一条角色任务，该角色任一人通过即办结</li>
 *   <li>{@link #MODE_ALL}（会签）：给该角色当前所有启用账号各建一条任务，全部通过才办结</li>
 * </ul>
 * 当前只支持"处理人 = 角色"；指定人到账号的模型已留字段（{@code assigneeType}）。
 *
 * @param seq 从 1 开始，与 {@code approval_task.level} 一致，便于历史查询兼容
 */
public record ApprovalNode(Long id, Long templateId, int seq, String nodeName,
                           int assigneeType, String assigneeRole, String approveMode,
                           boolean allowEditGates) {

    /** 处理人类型：角色（该角色任一账号可处理）。 */
    public static final int TYPE_ROLE = 2;

    public static final String MODE_SINGLE = "SINGLE";
    public static final String MODE_ALL = "ALL";
    public static final String MODE_ANY = "ANY";

    public static ApprovalNode of(Long templateId, int seq, String nodeName, String role,
                                  String mode, boolean allowEditGates) {
        return new ApprovalNode(null, templateId, seq, nodeName, TYPE_ROLE, role,
                normalizeMode(mode), allowEditGates);
    }

    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_SINGLE;
        }
        String upper = mode.trim().toUpperCase();
        return switch (upper) {
            case MODE_ALL, MODE_ANY, MODE_SINGLE -> upper;
            default -> MODE_SINGLE;
        };
    }

    public boolean allMode() {
        return MODE_ALL.equals(approveMode);
    }

    public String modeText() {
        return switch (approveMode) {
            case MODE_ALL -> "会签（全部通过）";
            case MODE_ANY -> "或签（任一人通过）";
            default -> "单签";
        };
    }
}
