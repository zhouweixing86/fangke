package com.fangke.visitor.approval.service;

/**
 * 按预约单催办（保安工作台用）。抽成小接口，避免保安模块依赖整个审批服务。
 */
public interface ReminderPort {

    /**
     * @return 剩余可催办次数
     */
    int remindByAppointment(Long appointmentId, Long operatorId);
}
