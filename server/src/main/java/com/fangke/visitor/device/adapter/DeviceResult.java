package com.fangke.visitor.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 设备接口返回的通用信封：{@code {"message": "...", "result": 0, "data": ...}}。
 */
public record DeviceResult(int result, String message, JsonNode data) {

    public boolean success() {
        return result == 0;
    }
}
