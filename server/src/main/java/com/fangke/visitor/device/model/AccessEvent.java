package com.fangke.visitor.device.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 通行事件（docs/10 §8 access_event）。设备上报原始记录，不做业务解读。
 *
 * @param eventUuid    设备上报的 {@code id}，幂等键
 * @param direction    0 出 / 1 进，取设备 {@code inout}，平台不推断
 * @param identifyType 0 人脸 / 2 人证 / 3 IC / 5 二维码
 * @param resultStatus 1 成功 / 0 失败
 * @param source       1 设备回调 / 2 在线比对 / 3 手工补录
 */
public record AccessEvent(
        String eventUuid,
        Long deviceId,
        String deviceSn,
        Long gateId,
        int direction,
        LocalDateTime eventTime,
        int identifyType,
        String personNo,
        String personName,
        String idCardMask,
        int resultStatus,
        String failReason,
        BigDecimal score,
        BigDecimal temperature,
        boolean mateFlag,
        int abnormalFlag,
        int source,
        String rawJson
) {

    public boolean success() {
        return resultStatus == 1;
    }
}
