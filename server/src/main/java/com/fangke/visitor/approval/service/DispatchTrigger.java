package com.fangke.visitor.approval.service;

import com.fangke.visitor.appointment.model.Appointment;

/**
 * 终审通过后的授权下发触发点（M6 实现）。
 *
 * <p>M5 只负责把预约单推进到「已通过」并触发本接口；真正的
 * 生成 access_grant、推送设备、跟踪下发结果在 M6。
 */
public interface DispatchTrigger {

    void trigger(Appointment appointment);

    static DispatchTrigger noop() {
        return appointment -> {
        };
    }
}
