package com.fangke.visitor.device.service;

/**
 * 对账结果落库（docs/10 §7 person_device_state），用于看板与差异追踪。
 */
public interface PersonStateStore {

    int EXPECT_IN = 1;
    int EXPECT_OUT = 2;
    int ACTUAL_IN = 1;
    int ACTUAL_OUT = 2;
    int ACTUAL_UNKNOWN = 3;

    void save(Long deviceId, String personNo, int expectState, int actualState, boolean diff);
}
