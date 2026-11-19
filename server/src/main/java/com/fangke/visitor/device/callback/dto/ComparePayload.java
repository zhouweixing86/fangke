package com.fangke.visitor.device.callback.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 在线比对请求（docs/06 §3.0 在线比对识别）。
 *
 * <p>{@code type}：{@code qr} 扫码 / {@code id} 人证 / {@code face} 人脸 / {@code ic} 刷卡 / {@code stranger} 陌生人。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComparePayload {

    @JsonProperty("dev_sno")
    private String devSno;

    @JsonProperty("deviceId")
    private String deviceId;

    @JsonProperty("deviceSN")
    private String deviceSn;

    @JsonProperty("token")
    private String token;

    @JsonProperty("type")
    private String type;

    @JsonProperty("value")
    private String value;

    @JsonProperty("name")
    private String name;

    @JsonProperty("temperature")
    private String temperature;

    @JsonProperty("data")
    private Data data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @JsonProperty("capture")
        private String capture;
        @JsonProperty("time")
        private String time;

        public String getCapture() {
            return capture;
        }

        public void setCapture(String capture) {
            this.capture = capture;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }
    }

    /** 设备码或 SN：优先 deviceSN，其次 dev_sno（MAC）。 */
    public String resolveDeviceSn() {
        return (deviceSn != null && !deviceSn.isBlank()) ? deviceSn : devSno;
    }

    public String getDevSno() {
        return devSno;
    }

    public void setDevSno(String devSno) {
        this.devSno = devSno;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceSn() {
        return deviceSn;
    }

    public void setDeviceSn(String deviceSn) {
        this.deviceSn = deviceSn;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTemperature() {
        return temperature;
    }

    public void setTemperature(String temperature) {
        this.temperature = temperature;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }
}
