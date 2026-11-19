package com.fangke.visitor.appointment.service;

import com.fangke.visitor.appointment.model.Appointment;

/**
 * 审批流启动钩子（M5 实现）。
 *
 * <p>M4 只负责把预约单落库并置为「待审批」；真正的审批任务创建、审批人解析、
 * 二级审批人选择在 M5 完成。用接口隔开，M4 可以先独立验收。
 */
public interface ApprovalStarter {

    void start(Appointment appointment);

    /** 预约单撤回/撤销时清理未完成审批任务（M5 实现）。 */
    default void cancel(Appointment appointment) {
    }

    static ApprovalStarter noop() {
        return appointment -> {
        };
    }
}
