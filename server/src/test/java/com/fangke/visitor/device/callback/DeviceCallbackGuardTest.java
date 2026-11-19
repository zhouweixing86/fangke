package com.fangke.visitor.device.callback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回调来源校验只做一层兜底，但配错会导致设备全部被拒，必须有测试。
 */
class DeviceCallbackGuardTest {

    @Test
    @DisplayName("精确 IP 匹配")
    void exactIp() {
        assertTrue(DeviceCallbackGuard.matches("192.168.10.135", "192.168.10.135"));
        assertFalse(DeviceCallbackGuard.matches("192.168.10.136", "192.168.10.135"));
    }

    @Test
    @DisplayName("CIDR 网段匹配")
    void cidr() {
        assertTrue(DeviceCallbackGuard.matches("192.168.10.135", "192.168.10.0/24"));
        assertFalse(DeviceCallbackGuard.matches("192.168.11.135", "192.168.10.0/24"));
    }

    @Test
    @DisplayName("非 8 位边界的掩码")
    void nonByteAlignedMask() {
        // /25 只覆盖 192.168.10.0 ~ 192.168.10.127
        assertTrue(DeviceCallbackGuard.matches("192.168.10.5", "192.168.10.0/25"));
        assertFalse(DeviceCallbackGuard.matches("192.168.10.200", "192.168.10.0/25"));
    }

    @Test
    @DisplayName("/0 视为全部放行；非法输入一律拒绝")
    void edgeCases() {
        assertTrue(DeviceCallbackGuard.matches("10.1.2.3", "0.0.0.0/0"));
        assertFalse(DeviceCallbackGuard.matches(null, "192.168.10.0/24"));
        assertFalse(DeviceCallbackGuard.matches("192.168.10.5", null));
        assertFalse(DeviceCallbackGuard.matches("192.168.10.5", "192.168.10.0/abc"));
        assertFalse(DeviceCallbackGuard.matches("192.168.10.5", "192.168.10.0/33"));
    }
}
