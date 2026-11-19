package com.fangke.visitor.approval.service;

/**
 * 通知出口（订阅消息 / 待办角标 / 催办）。M5 只定义接口，真正的推送在 P3 小程序侧实现。
 */
public interface NotifyPort {

    /** 有新待办（角标 +1）。 */
    void approvalTodo(Long employeeId, Long appointmentId);

    /** 审批结果通知申请人。 */
    void approvalResult(Long appointmentId, boolean passed, String reason);

    /** 催办提醒审批人。 */
    void reminder(Long employeeId, Long taskId);

    static NotifyPort noop() {
        return new NotifyPort() {
            @Override
            public void approvalTodo(Long employeeId, Long appointmentId) {
            }

            @Override
            public void approvalResult(Long appointmentId, boolean passed, String reason) {
            }

            @Override
            public void reminder(Long employeeId, Long taskId) {
            }
        };
    }
}
