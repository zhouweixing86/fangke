package com.fangke.visitor.approval.service;

/**
 * 审批抄送出口（V24）。
 *
 * <p>抄送**只通知、不阻断流程**，所以单独抽一个端口：审批服务只管"什么时候该抄送"，
 * 具体发给谁（角色下的哪些账号）、走什么渠道（当前只站内）由实现决定。
 */
public interface CcPort {

    /**
     * 按角色抄送：给该角色下所有启用账号各发一条站内消息。
     *
     * @param stage 触发点描述，如「提交时」「节点『保安审核』通过后」，用于消息文案与去重
     */
    void ccToRole(String roleCode, Long appointmentId, String appointmentNo, String visitorName,
                  String timeRange, String stage);

    static CcPort noop() {
        return (roleCode, appointmentId, appointmentNo, visitorName, timeRange, stage) -> {
        };
    }
}
