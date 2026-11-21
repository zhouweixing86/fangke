package com.fangke.visitor.appointment.model;

import java.time.LocalDateTime;

/**
 * 预约单（docs/10 §5 appointment，状态码见 §5.1）。
 */
public class Appointment {

    public static final int DRAFT = 10;
    public static final int PENDING_L1 = 20;
    public static final int PENDING_L2 = 25;
    public static final int APPROVED = 30;
    public static final int GRANTED = 35;
    public static final int CHECKED_IN = 40;
    public static final int CHECKED_OUT = 50;
    public static final int REJECTED = 60;
    public static final int EXPIRED = 70;
    public static final int WITHDRAWN = 80;
    public static final int REVOKED = 90;

    /** 来源：1 访客自约 2 员工代邀 3 现场自助登记。 */
    public static final int SOURCE_SELF = 1;
    public static final int SOURCE_INVITE = 2;
    public static final int SOURCE_ONSITE = 3;

    /** 同行人模式：0 随行（主客一次放行全部） 1 各自刷证。 */
    public static final int MATE_TOGETHER = 0;
    public static final int MATE_SEPARATE = 1;

    /** 访客申请口径：1 指定门点 2 全部门点（C-64）。 */
    public static final int GATE_SCOPE_SPECIFIED = 1;
    public static final int GATE_SCOPE_ALL = 2;

    /** 来访时段：访客端只选全天/上午/下午，由后端换算 start/end。 */
    public static final int PERIOD_ALL_DAY = 1;
    public static final int PERIOD_MORNING = 2;
    public static final int PERIOD_AFTERNOON = 3;

    private Long id;
    private String appointmentNo;
    private int source;
    private Long visitorId;
    private Long visitEmployeeId;
    private byte[] visitPhoneEnc;
    private String visitPhoneMask;
    private String visitorType;
    private String purpose;
    /** 来访公司（团体预约时为负责人公司） */
    private String company;
    /** 来访人职务 */
    private String position;
    /** 时段：1 全天 / 2 上午 / 3 下午 */
    private int period = PERIOD_ALL_DAY;
    private String plateNo;
    private String department;
    /** 预约门点（主门点）；null 表示沿用 fangke.gate.default-id（兼容历史单据与单门部署）。 */
    private Long gateId;
    /** 访客申请口径：1 指定门点 2 全部门点（C-64）。 */
    private int gateScope = GATE_SCOPE_SPECIFIED;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int mateMode;
    private int mateCount;
    /** 访客申请二维码开门（0 否 / 1 是）：预约时访客勾选。 */
    private boolean qrRequested;
    /** 审批人是否同意二维码开门（0 否 / 1 是）：同意后访客端才能取码。 */
    private boolean qrApproved;
    private Long qrApprovedBy;
    private LocalDateTime qrApprovedAt;
    private int approvalLevel = 1;
    private int status = PENDING_L1;
    private String rejectReason;
    private String cancelReason;
    private Long currentTaskId;
    private LocalDateTime grantedAt;
    private LocalDateTime createdAt;

    public static String statusText(int status) {
        return switch (status) {
            case DRAFT -> "草稿";
            case PENDING_L1 -> "待审批";
            case PENDING_L2 -> "待保安复核";
            case APPROVED -> "已通过";
            case GRANTED -> "可通行";
            case CHECKED_IN -> "已签到";
            case CHECKED_OUT -> "已签离";
            case REJECTED -> "已驳回";
            case EXPIRED -> "已过期";
            case WITHDRAWN -> "已撤回";
            case REVOKED -> "已撤销";
            default -> "未知";
        };
    }

    /** 是否处于进行中（用于"已有进行中的预约"校验）。 */
    public static boolean isInProgress(int status) {
        return status == DRAFT || status == PENDING_L1 || status == PENDING_L2
                || status == APPROVED || status == GRANTED || status == CHECKED_IN;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAppointmentNo() {
        return appointmentNo;
    }

    public void setAppointmentNo(String appointmentNo) {
        this.appointmentNo = appointmentNo;
    }

    public int getSource() {
        return source;
    }

    public void setSource(int source) {
        this.source = source;
    }

    public Long getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(Long visitorId) {
        this.visitorId = visitorId;
    }

    public Long getVisitEmployeeId() {
        return visitEmployeeId;
    }

    public void setVisitEmployeeId(Long visitEmployeeId) {
        this.visitEmployeeId = visitEmployeeId;
    }

    public byte[] getVisitPhoneEnc() {
        return visitPhoneEnc;
    }

    public void setVisitPhoneEnc(byte[] visitPhoneEnc) {
        this.visitPhoneEnc = visitPhoneEnc;
    }

    public String getVisitPhoneMask() {
        return visitPhoneMask;
    }

    public void setVisitPhoneMask(String visitPhoneMask) {
        this.visitPhoneMask = visitPhoneMask;
    }

    public String getVisitorType() {
        return visitorType;
    }

    public void setVisitorType(String visitorType) {
        this.visitorType = visitorType;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public int getPeriod() {
        return period;
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public static String periodText(int period) {
        return switch (period) {
            case PERIOD_MORNING -> "上午";
            case PERIOD_AFTERNOON -> "下午";
            default -> "全天";
        };
    }

    public String getPlateNo() {
        return plateNo;
    }

    public void setPlateNo(String plateNo) {
        this.plateNo = plateNo;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public Long getGateId() {
        return gateId;
    }

    public void setGateId(Long gateId) {
        this.gateId = gateId;
    }

    public int getGateScope() {
        return gateScope;
    }

    public void setGateScope(int gateScope) {
        this.gateScope = gateScope;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public int getMateMode() {
        return mateMode;
    }

    public void setMateMode(int mateMode) {
        this.mateMode = mateMode;
    }

    public boolean isQrRequested() {
        return qrRequested;
    }

    public void setQrRequested(boolean qrRequested) {
        this.qrRequested = qrRequested;
    }

    public boolean isQrApproved() {
        return qrApproved;
    }

    public void setQrApproved(boolean qrApproved) {
        this.qrApproved = qrApproved;
    }

    public Long getQrApprovedBy() {
        return qrApprovedBy;
    }

    public void setQrApprovedBy(Long qrApprovedBy) {
        this.qrApprovedBy = qrApprovedBy;
    }

    public LocalDateTime getQrApprovedAt() {
        return qrApprovedAt;
    }

    public void setQrApprovedAt(LocalDateTime qrApprovedAt) {
        this.qrApprovedAt = qrApprovedAt;
    }

    public int getMateCount() {
        return mateCount;
    }

    public void setMateCount(int mateCount) {
        this.mateCount = mateCount;
    }

    public int getApprovalLevel() {
        return approvalLevel;
    }

    public void setApprovalLevel(int approvalLevel) {
        this.approvalLevel = approvalLevel;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public Long getCurrentTaskId() {
        return currentTaskId;
    }

    public void setCurrentTaskId(Long currentTaskId) {
        this.currentTaskId = currentTaskId;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(LocalDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
