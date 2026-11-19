package com.fangke.visitor.common;

import com.fangke.visitor.device.adapter.DeviceException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * 异常 → HTTP 状态映射（docs/11 §0.7）：设备异常不能再一律变成 5001。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    @DisplayName("设备离线映射 503 + 4001")
    void deviceOffline() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleDevice(
                new DeviceException(ErrorCode.DEVICE_OFFLINE, "连接超时"), request);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.DEVICE_OFFLINE.code(), resp.getBody().getCode());
    }

    @Test
    @DisplayName("能力不支持映射 501 + 4006，并保留可读原因")
    void capabilityUnsupported() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleDevice(
                new DeviceException(ErrorCode.DEVICE_UNSUPPORTED, "设备不支持「屏幕提示」"), request);
        assertEquals(HttpStatus.NOT_IMPLEMENTED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.DEVICE_UNSUPPORTED.code(), resp.getBody().getCode());
        assertEquals("设备不支持「屏幕提示」", resp.getBody().getMessage());
    }

    @Test
    @DisplayName("设备口令错误映射 502 + 4002")
    void devicePassword() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleDevice(
                new DeviceException(ErrorCode.DEVICE_PASSWORD, "口令错误"), request);
        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.DEVICE_PASSWORD.code(), resp.getBody().getCode());
    }

    @Test
    @DisplayName("业务异常仍按原映射：命中黑名单 403")
    void businessBlacklisted() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusiness(
                new BusinessException(ErrorCode.BLACKLISTED, "命中黑名单"), request);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.BLACKLISTED.code(), resp.getBody().getCode());
    }
}
