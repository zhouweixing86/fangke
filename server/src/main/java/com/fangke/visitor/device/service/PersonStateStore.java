package com.fangke.visitor.device.service;

import java.util.List;

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

    /**
     * 平台认为「这台设备上应该有人」的名单（最近一次对账留下的 EXPECT_IN 记录）。
     *
     * <p>改数据时绕过停职、或者在设备上手工登记过人，会在这里留下平台不认的人员编号，
     * 用它算差集就能把设备拉回平台的账。
     */
    List<String> expectedIn(Long deviceId);
}
