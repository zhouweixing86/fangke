package com.fangke.visitor.device.adapter;

import com.fangke.visitor.common.ErrorCode;

/**
 * 设备通道异常。code 取 4xxx 段（docs/11 §0.7）。
 */
public class DeviceException extends RuntimeException {

    private final int code;

    public DeviceException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.code();
    }

    public DeviceException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.code();
    }

    public int getCode() {
        return code;
    }
}
