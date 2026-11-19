package com.fangke.visitor.device.service;

import java.time.Instant;

/**
 * 授权状态查询（在线比对用）。生产实现查库，单测可用桩实现。
 */
public interface GrantChecker {

    enum State { OK, NO_GRANT, OUT_OF_WINDOW, GATE_MISMATCH }

    /**
     * @param appointmentNo 预约单号
     * @param gateId        设备所在门点，null 表示不校验门点
     * @param now           判定时刻
     */
    State check(String appointmentNo, Long gateId, Instant now);

    /**
     * 该预约授权对应的门点（保安端核验没有设备，需要回填 visit_record.gate_id）。
     *
     * @return 门点 ID；查不到返回 null
     */
    default Long resolveGateId(String appointmentNo) {
        return null;
    }
}
