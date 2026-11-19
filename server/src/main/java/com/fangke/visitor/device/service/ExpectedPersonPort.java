package com.fangke.visitor.device.service;

import java.time.Instant;
import java.util.List;

/**
 * 平台期望名单来源（对账用）。
 *
 * <p>P1 只覆盖访客授权（access_grant）；员工名单在 M2 完成后接入同一端口。
 */
public interface ExpectedPersonPort {

    record ExpectedPerson(String personNo, String userType) {
    }

    List<ExpectedPerson> expectedOn(Long gateId, Instant now);
}
