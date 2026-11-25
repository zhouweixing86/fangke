package com.fangke.visitor.vehicle.cloud;

/** 云车场接口调用失败（网络/签名/业务 code 非 1）。 */
public class CloudParkingException extends RuntimeException {

    public CloudParkingException(String message) {
        super(message);
    }

    public CloudParkingException(String message, Throwable cause) {
        super(message, cause);
    }
}
