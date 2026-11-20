package com.fangke.visitor.device.pull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 下发给设备的有效期必须落在 32 位时间戳范围内。
 *
 * <p>2026-11-20 真机实测：平台用 {@code 9999-12-31} 表示永久有效，设备换算后直接显示"已过期"，
 * 刷脸和刷身份证都进不去。这里锁住截断逻辑，避免以后又改回去。
 */
class OpenApiPersonValidPeriodTest {

    @Test
    @DisplayName("9999-12-31 的永久有效期截断到 2037-12-31")
    void clampForeverToDeviceMax() {
        assertEquals(OpenApiDeviceService.DEVICE_MAX_VALID_TO,
                OpenApiDeviceService.deviceValidTo(LocalDateTime.of(9999, 12, 31, 23, 59, 59)));
    }

    @Test
    @DisplayName("结束日期为空时按上限下发，不传空值")
    void nullEndBecomesDeviceMax() {
        assertEquals(OpenApiDeviceService.DEVICE_MAX_VALID_TO,
                OpenApiDeviceService.deviceValidTo(null));
    }

    @Test
    @DisplayName("正常访客有效期原样下发")
    void normalWindowKept() {
        LocalDateTime end = LocalDateTime.of(2026, 11, 21, 18, 0);
        assertEquals(end, OpenApiDeviceService.deviceValidTo(end));
    }
}
