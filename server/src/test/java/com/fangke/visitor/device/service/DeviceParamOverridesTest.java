package com.fangke.visitor.device.service;

import com.fangke.visitor.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 心跳下发设备参数：白名单 + 期望值合并（C-85）。 */
class DeviceParamOverridesTest {

    @Test
    @DisplayName("合并只接受白名单键，布尔归一成 0/1")
    void mergeKeepsWhitelist() {
        String json = DeviceParamOverrides.merge(null, Map.of("videoScanQRcode", true));
        assertEquals(Map.of("videoScanQRcode", 1), DeviceParamOverrides.parse(json));

        String merged = DeviceParamOverrides.merge(json, Map.of("PasswordOpenEnable", false, "QROpenEnable", "1"));
        Map<String, Integer> parsed = DeviceParamOverrides.parse(merged);
        assertEquals(1, parsed.get("videoScanQRcode"));
        assertEquals(0, parsed.get("PasswordOpenEnable"));
        assertEquals(1, parsed.get("QROpenEnable"));
    }

    @Test
    @DisplayName("白名单外的参数（如平台地址、IP）拒绝下发")
    void mergeRejectsUnknownKeys() {
        assertThrows(BusinessException.class,
                () -> DeviceParamOverrides.merge(null, Map.of("thirdHttpUrl", "http://evil")));
        assertThrows(BusinessException.class,
                () -> DeviceParamOverrides.merge(null, Map.of("videoScanQRcode", "maybe")));
        assertThrows(BusinessException.class, () -> DeviceParamOverrides.merge(null, Map.of()));
    }

    @Test
    @DisplayName("下发时期望值覆盖平台默认值，脏数据不影响其余字段")
    void applyToOverridesDefaults() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("thirdOpenDoorLocal", 0);
        data.put("videoScanQRcode", 0);
        data.put("name", "大门入口");

        DeviceParamOverrides.applyTo("{\"videoScanQRcode\":1,\"unknown\":1}", data);

        assertEquals(1, data.get("videoScanQRcode"));
        assertEquals(0, data.get("thirdOpenDoorLocal"));
        assertEquals("大门入口", data.get("name"));
        assertFalse(data.containsKey("unknown"));
    }

    @Test
    @DisplayName("坏 JSON 与空值解析成空期望值，不抛异常")
    void parseToleratesBadInput() {
        assertTrue(DeviceParamOverrides.parse(null).isEmpty());
        assertTrue(DeviceParamOverrides.parse("").isEmpty());
        assertTrue(DeviceParamOverrides.parse("not-json").isEmpty());
        assertTrue(DeviceParamOverrides.parse("{\"thirdHttpUrl\":\"x\"}").isEmpty());
    }
}
