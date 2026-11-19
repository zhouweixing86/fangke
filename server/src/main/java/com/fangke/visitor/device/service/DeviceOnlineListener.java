package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.Device;

/**
 * 设备上线回调（docs/02 JOB-04：设备上线时触发一次对账）。
 */
public interface DeviceOnlineListener {

    void onDeviceOnline(Device device);

    static DeviceOnlineListener noop() {
        return device -> {
        };
    }
}
