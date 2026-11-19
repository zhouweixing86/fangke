package com.fangke.visitor.device.callback.dto;

/**
 * 心跳 / 记录回调的应答体。
 *
 * <p><b>必须</b>原样返回 {@code {"message":"ok","result":0}}，否则设备会无限重推
 * （docs/06 §0.3 第 3 条、docs/11 §1.2）。
 */
public record DeviceAck(String message, int result) {

    public static final DeviceAck OK = new DeviceAck("ok", 0);

    public static DeviceAck ok() {
        return OK;
    }
}
