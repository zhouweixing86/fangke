package com.fangke.visitor.employee.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工（docs/10 §3 employee）。
 *
 * <p>手机号与身份证号只保留密文 / HMAC / 脱敏三份；明文仅在服务层短暂持有，不落库。
 */
public class Employee {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_PENDING_REVIEW = 2;
    public static final int STATUS_DISABLED = 3;

    public static final int FACE_NONE = 0;
    public static final int FACE_UPLOADED = 1;
    public static final int FACE_SYNCING = 2;
    public static final int FACE_EFFECTIVE = 3;

    private Long id;
    private String personNo;
    private String empNo;
    private String name;
    private Long deptId;
    private String deptName;
    private Long leaderId;
    private String title;
    /** 车牌号（主车），二期车辆识别用（C-67）。 */
    private String plateNo;

    /** 明文，仅在服务层使用，不序列化。 */
    private transient String phonePlain;
    private byte[] phoneEnc;
    private String phoneHmac;
    private String phoneMask;

    private transient String idCardPlain;
    private byte[] idCardEnc;
    private String idCardHmac;
    private String idCardMask;

    private int status = STATUS_ACTIVE;
    private LocalDate validFrom;
    private LocalDate validTo;
    private int faceStatus = FACE_NONE;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 业务编号规则：E + 6 位工号（docs/01 §6.2）。 */
    public static String personNoOf(String empNo) {
        String digits = empNo == null ? "" : empNo.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        String six = digits.length() >= 6 ? digits.substring(digits.length() - 6)
                : "0".repeat(6 - digits.length()) + digits;
        return "E" + six;
    }

    /** 能否刷脸通行：在职 + 有照片 + 在有效期内。 */
    public boolean canPassByFace(LocalDate today) {
        if (status != STATUS_ACTIVE || faceStatus == FACE_NONE) {
            return false;
        }
        if (validFrom != null && today.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || !today.isAfter(validTo);
    }

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

    public String getEmpNo() {
        return empNo;
    }

    public void setEmpNo(String empNo) {
        this.empNo = empNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public Long getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(Long leaderId) {
        this.leaderId = leaderId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
    public String getPlateNo() {
        return plateNo;
    }

    public void setPlateNo(String plateNo) {
        this.plateNo = normalizePlate(plateNo);
    }

    /** 车牌统一去空格转大写，方便二期车牌识别直接匹配。 */
    private static String normalizePlate(String plate) {
        if (plate == null) {
            return null;
        }
        String trimmed = plate.replace(" ", "").replace("\t", "").trim().toUpperCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String getPhonePlain() {
        return phonePlain;
    }

    public void setPhonePlain(String phonePlain) {
        this.phonePlain = phonePlain;
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

    public String getIdCardPlain() {
        return idCardPlain;
    }

    public void setIdCardPlain(String idCardPlain) {
        this.idCardPlain = idCardPlain;
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

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
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

    public int getFaceStatus() {
        return faceStatus;
    }

    public void setFaceStatus(int faceStatus) {
        this.faceStatus = faceStatus;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
