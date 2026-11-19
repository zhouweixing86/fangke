package com.fangke.visitor.device.callback.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 通行记录上报报文（docs/06 2.6.1 / docs/11 §1.2）。
 *
 * <p>字段名保持设备原始风格（下划线/驼峰混用），**不要改**。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecordPayload {

    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @JsonProperty("id")
    private String id;

    @JsonProperty("Mac_addr")
    private String macAddr;

    @JsonProperty("SN")
    private String sn;

    @JsonProperty("time")
    private String time;

    @JsonProperty("inout")
    private Integer inout;

    @JsonProperty("employee_number")
    private String employeeNumber;

    @JsonProperty("name")
    private String name;

    @JsonProperty("idNum")
    private String idNum;

    @JsonProperty("IdentifyType")
    private String identifyType;

    @JsonProperty("resultStatus")
    private Integer resultStatus;

    @JsonProperty("face_base64")
    private String faceBase64;

    @JsonProperty("temperature")
    private String temperature;

    @JsonProperty("healthCode")
    private Integer healthCode;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMacAddr() {
        return macAddr;
    }

    public void setMacAddr(String macAddr) {
        this.macAddr = macAddr;
    }

    public String getSn() {
        return sn;
    }

    public void setSn(String sn) {
        this.sn = sn;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public Integer getInout() {
        return inout;
    }

    public void setInout(Integer inout) {
        this.inout = inout;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public void setEmployeeNumber(String employeeNumber) {
        this.employeeNumber = employeeNumber;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIdNum() {
        return idNum;
    }

    public void setIdNum(String idNum) {
        this.idNum = idNum;
    }

    public String getIdentifyType() {
        return identifyType;
    }

    public void setIdentifyType(String identifyType) {
        this.identifyType = identifyType;
    }

    public Integer getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(Integer resultStatus) {
        this.resultStatus = resultStatus;
    }

    public String getFaceBase64() {
        return faceBase64;
    }

    public void setFaceBase64(String faceBase64) {
        this.faceBase64 = faceBase64;
    }

    public String getTemperature() {
        return temperature;
    }

    public void setTemperature(String temperature) {
        this.temperature = temperature;
    }

    public Integer getHealthCode() {
        return healthCode;
    }

    public void setHealthCode(Integer healthCode) {
        this.healthCode = healthCode;
    }

    public LocalDateTime eventTime() {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(time.trim(), DEVICE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public int directionOrDefault() {
        return inout == null ? 1 : inout;
    }

    public int identifyTypeCode() {
        if (identifyType == null || identifyType.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(identifyType.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int resultStatusOrDefault() {
        return resultStatus == null ? 0 : resultStatus;
    }

    public BigDecimal temperatureOrNull() {
        if (temperature == null || temperature.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(temperature.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
