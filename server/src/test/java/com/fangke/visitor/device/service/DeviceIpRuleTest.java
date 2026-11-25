package com.fangke.visitor.device.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 心跳上报的出口 IP 只有是内网地址时才覆盖 device.ip，避免「本地管理」跳到公网出口。 */
class DeviceIpRuleTest {

    @Test
    @DisplayName("内网地址可覆盖设备 IP")
    void privateAddressesAreAccepted() {
        assertTrue(JdbcDeviceRepository.isPrivateAddress("192.168.0.103"));
        assertTrue(JdbcDeviceRepository.isPrivateAddress("10.1.2.3"));
        assertTrue(JdbcDeviceRepository.isPrivateAddress("172.16.5.9"));
        assertTrue(JdbcDeviceRepository.isPrivateAddress("172.31.0.1"));
        assertTrue(JdbcDeviceRepository.isPrivateAddress("127.0.0.1"));
        assertTrue(JdbcDeviceRepository.isPrivateAddress(" 192.168.0.103 "));
    }

    @Test
    @DisplayName("公网地址与空值不覆盖设备 IP")
    void publicAddressesAreRejected() {
        assertFalse(JdbcDeviceRepository.isPrivateAddress("117.89.73.58"));
        assertFalse(JdbcDeviceRepository.isPrivateAddress("172.32.0.1"));
        assertFalse(JdbcDeviceRepository.isPrivateAddress("172.15.0.1"));
        assertFalse(JdbcDeviceRepository.isPrivateAddress("8.8.8.8"));
        assertFalse(JdbcDeviceRepository.isPrivateAddress(null));
        assertFalse(JdbcDeviceRepository.isPrivateAddress("  "));
    }
}
