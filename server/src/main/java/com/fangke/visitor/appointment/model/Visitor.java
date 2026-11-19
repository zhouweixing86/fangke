package com.fangke.visitor.appointment.model;

import java.time.LocalDateTime;

/**
 * 访客档案（docs/10 §5 visitor）。按身份证号 HMAC 复用：同一人多次来访只有一条。
 */
public class Visitor {

    private Long id;
    private String personNo;
    private String name;
    private byte[] phoneEnc;
    private String phoneHmac;
    private String phoneMask;
    private byte[] idCardEnc;
    private String idCardHmac;
    private String idCardMask;
    private String company;
    /** 人脸压缩件对象 key（MinIO，V14）；为空表示未采集照片，只能刷证/扫码。 */
    private String faceOssKey;
    private int faceStatus;
    private LocalDateTime firstVisitAt;
    private LocalDateTime lastVisitAt;
    private int visitCount;
    private boolean blacklist;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPersonNo() {
        return personNo;
    }

    public void setPersonNo(String personNo) {
        this.personNo = personNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public byte[] getPhoneEnc() {
        return phoneEnc;
    }

    public void setPhoneEnc(byte[] phoneEnc) {
        this.phoneEnc = phoneEnc;
    }

    public String getPhoneHmac() {
        return phoneHmac;
    }

    public void setPhoneHmac(String phoneHmac) {
        this.phoneHmac = phoneHmac;
    }

    public String getPhoneMask() {
        return phoneMask;
    }

    public void setPhoneMask(String phoneMask) {
        this.phoneMask = phoneMask;
    }

    public byte[] getIdCardEnc() {
        return idCardEnc;
    }

    public void setIdCardEnc(byte[] idCardEnc) {
        this.idCardEnc = idCardEnc;
    }

    public String getIdCardHmac() {
        return idCardHmac;
    }

    public void setIdCardHmac(String idCardHmac) {
        this.idCardHmac = idCardHmac;
    }

    public String getIdCardMask() {
        return idCardMask;
    }

    public void setIdCardMask(String idCardMask) {
        this.idCardMask = idCardMask;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getFaceOssKey() {
        return faceOssKey;
    }

    public void setFaceOssKey(String faceOssKey) {
        this.faceOssKey = faceOssKey;
    }

    public int getFaceStatus() {
        return faceStatus;
    }

    public void setFaceStatus(int faceStatus) {
        this.faceStatus = faceStatus;
    }

    public LocalDateTime getFirstVisitAt() {
        return firstVisitAt;
    }

    public void setFirstVisitAt(LocalDateTime firstVisitAt) {
        this.firstVisitAt = firstVisitAt;
    }

    public LocalDateTime getLastVisitAt() {
        return lastVisitAt;
    }

    public void setLastVisitAt(LocalDateTime lastVisitAt) {
        this.lastVisitAt = lastVisitAt;
    }

    public int getVisitCount() {
        return visitCount;
    }

    public void setVisitCount(int visitCount) {
        this.visitCount = visitCount;
    }

    public boolean isBlacklist() {
        return blacklist;
    }

    public void setBlacklist(boolean blacklist) {
        this.blacklist = blacklist;
    }
}
