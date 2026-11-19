package com.fangke.visitor.device.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 设备日期格式是历史踩坑点（docs/06 §0.3 第 1 条），单独锁死。
 */
class DeviceDateFormatsTest {

    @Test
    @DisplayName("下发日期必须带一个尾随空格")
    void formatKeepsTrailingSpace() {
        String formatted = DeviceDateFormats.format(LocalDate.of(2026, 11, 16));
        assertEquals("2026-11-16 ", formatted);
        assertEquals(' ', formatted.charAt(formatted.length() - 1));
    }

    @Test
    @DisplayName("回读设备值时可容错解析（带或不带空格）")
    void parseToleratesBoth() {
        assertEquals(LocalDate.of(2026, 11, 16), DeviceDateFormats.parse("2026-11-16 "));
        assertEquals(LocalDate.of(2026, 11, 16), DeviceDateFormats.parse("2026-11-16"));
        assertEquals(LocalDate.of(2026, 11, 16), DeviceDateFormats.parse(" 2026-11-16 "));
        assertNull(DeviceDateFormats.parse("  "));
        assertNull(DeviceDateFormats.parse(null));
    }
}
