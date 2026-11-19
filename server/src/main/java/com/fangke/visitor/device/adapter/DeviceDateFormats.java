package com.fangke.visitor.device.adapter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 设备日期格式：{@code yyyy-MM-dd} + <b>一个尾随空格</b>。
 *
 * <p>原文要求见 docs/06 §0.3 第 1 条；真机验证也以该格式下发。
 * 该格式化只允许出现在适配层，禁止上层自行拼字符串。
 */
public final class DeviceDateFormats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DeviceDateFormats() {
    }

    /** 例：{@code 2026-11-16 }（注意末尾空格）。 */
    public static String format(LocalDate date) {
        return date == null ? null : DATE.format(date) + " ";
    }

    /** 容错解析：接受带或不带尾随空格的日期，便于回读设备值。 */
    public static LocalDate parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : LocalDate.parse(trimmed, DATE);
    }
}
