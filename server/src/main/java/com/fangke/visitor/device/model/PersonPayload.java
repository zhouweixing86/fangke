package com.fangke.visitor.device.model;

import java.time.LocalDate;

/**
 * 下发到设备的名单条目。
 *
 * <p>关键约束（docs/06 §0.3）：
 * <ul>
 *   <li>日期必须格式化为 {@code yyyy-MM-dd} + <b>一个尾随空格</b>；</li>
 *   <li>访客不需要照片 → {@code passAlgo=true}；员工需要照片 → {@code passAlgo=false} + base64；</li>
 *   <li>有效期由平台算好（按自然日整段，C-60），不在设备侧推算。</li>
 * </ul>
 */
public class PersonPayload {

    /** 设备 usertype 取值。 */
    public static final String USER_TYPE_WHITE = "white";
    public static final String USER_TYPE_VISITOR = "visitor";
    public static final String USER_TYPE_BLACK = "black";

    private String personNo;
    private String name;
    private String idCard;
    private String userType = USER_TYPE_VISITOR;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String photoBase64;
    private Integer timeGroupId = 0;
    private Integer specialGroupId;

    public boolean requiresPhoto() {
        return photoBase64 != null && !photoBase64.isBlank();
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

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public String getPhotoBase64() {
        return photoBase64;
    }

    public void setPhotoBase64(String photoBase64) {
        this.photoBase64 = photoBase64;
    }

    public Integer getTimeGroupId() {
        return timeGroupId;
    }

    public void setTimeGroupId(Integer timeGroupId) {
        this.timeGroupId = timeGroupId;
    }

    public Integer getSpecialGroupId() {
        return specialGroupId;
    }

    public void setSpecialGroupId(Integer specialGroupId) {
        this.specialGroupId = specialGroupId;
    }
}
