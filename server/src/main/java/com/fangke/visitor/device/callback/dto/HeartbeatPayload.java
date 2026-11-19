package com.fangke.visitor.device.callback.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 心跳报文（实测原样，docs/11 §1.1）。
 *
 * <pre>
 * {"SN":"T77R781116ZS","currentTime":"1794793596","ip":"192.168.10.135","password":"123456","personCount":3}
 * </pre>
 *
 * <p>注意 {@code currentTime} 是 <b>Unix 秒字符串</b>，不是业务时间字符串。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HeartbeatPayload {

    /** 设备用大写 SN，必须显式映射（否则 SN 丢失 → 设备查不到）。 */
    @JsonProperty("SN")
    private String sn;
    private String currentTime;
    private String ip;
    private String password;
    private Integer personCount;

    public String getSn() {
        return sn;
    }

    public void setSn(String sn) {
        this.sn = sn;
    }

    public String getCurrentTime() {
        return currentTime;
    }

    public void setCurrentTime(String currentTime) {
        this.currentTime = currentTime;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getPersonCount() {
        return personCount;
    }

    public void setPersonCount(Integer personCount) {
        this.personCount = personCount;
    }

    /** 设备时间（秒级时间戳），解析失败返回 null。 */
    public Long epochSeconds() {
        if (currentTime == null || currentTime.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(currentTime.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
