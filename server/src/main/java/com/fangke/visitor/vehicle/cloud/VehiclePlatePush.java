package com.fangke.visitor.vehicle.cloud;

import java.time.LocalDateTime;

/**
 * 车闸车牌下发快照（{@code vehicle_plate_push}）：与车场开放接口差分对账用。
 *
 * <p>为什么需要它：{@code visitor_sync} 是按车牌增/改/删的接口，平台必须知道「上一次下发的内容」，
 * 否则每轮都会全量重发（车场侧可能当作重复访客），也删不掉已消失的车牌。
 */
public class VehiclePlatePush {

    /** 已成功下发。 */
    public static final String STATE_PUSHED = "PUSHED";
    /** 上次调用失败（保留记录，下一轮重试）。 */
    public static final String STATE_FAILED = "FAILED";

    private String plateNo;
    private String visitorId;
    private String ownerType;
    private Long ownerRef;
    private String personName;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private String fingerprint;
    private String state = STATE_PUSHED;
    private String lastError;
    private LocalDateTime pushedAt;

    public String getPlateNo() {
        return plateNo;
    }

    public void setPlateNo(String plateNo) {
        this.plateNo = plateNo;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(String visitorId) {
        this.visitorId = visitorId;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public void setOwnerType(String ownerType) {
        this.ownerType = ownerType;
    }

    public Long getOwnerRef() {
        return ownerRef;
    }

    public void setOwnerRef(Long ownerRef) {
        this.ownerRef = ownerRef;
    }

    public String getPersonName() {
        return personName;
    }

    public void setPersonName(String personName) {
        this.personName = personName;
    }

    public LocalDateTime getBeginTime() {
        return beginTime;
    }

    public void setBeginTime(LocalDateTime beginTime) {
        this.beginTime = beginTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getPushedAt() {
        return pushedAt;
    }

    public void setPushedAt(LocalDateTime pushedAt) {
        this.pushedAt = pushedAt;
    }
}
