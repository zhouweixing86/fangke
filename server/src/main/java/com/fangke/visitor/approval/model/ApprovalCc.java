package com.fangke.visitor.approval.model;

/**
 * 抄送配置（V24）。
 *
 * <p>抄送**只通知、不阻断流程**：
 * <ul>
 *   <li>{@code nodeId = null}：预约提交时抄送一次</li>
 *   <li>{@code nodeId != null}：该节点办结（通过）后抄送</li>
 * </ul>
 * 当前抄送对象只支持角色，渠道只支持站内通知（管理端消息中心）。
 */
public record ApprovalCc(Long id, Long templateId, Long nodeId, int targetType,
                         String targetValue, int channel, boolean enabled) {

    public static final int TARGET_ROLE = 2;
    public static final int CHANNEL_INBOX = 1;

    public static ApprovalCc toRole(Long templateId, Long nodeId, String role) {
        return new ApprovalCc(null, templateId, nodeId, TARGET_ROLE, role, CHANNEL_INBOX, true);
    }

    /** 提交时抄送（没有绑定节点）。 */
    public boolean onSubmit() {
        return nodeId == null;
    }
}
