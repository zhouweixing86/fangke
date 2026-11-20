package com.fangke.visitor.appointment.dto;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 预约单返回体：只出脱敏值，状态附中文文案（前端直接展示）。
 */
public record AppointmentView(
        String appointmentNo,
        int status,
        String statusText,
        int source,
        String visitorName,
        String visitorIdCardMask,
        String visitorType,
        String purpose,
        String visitPhoneMask,
        String department,
        Long gateId,
        String gateName,
        int gateScope,
        String gateScopeText,
        List<String> appliedGateNames,
        List<String> approvedGateNames,
        String startTime,
        String endTime,
        String visitDate,
        int mateMode,
        String mateModeText,
        List<Mate> mates,
        boolean canCancel,
        LocalDateTime createdAt,
        String company,
        String position,
        String periodText,
        String plateNo,
        int mateCount) {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record Mate(String name, String idCardMask, boolean main) {
    }

    public static AppointmentView of(Appointment a, String visitorName, String visitorIdCardMask,
                                     List<AppointmentMate> mates, String gateName,
                                     List<String> appliedGateNames, List<String> approvedGateNames) {
        List<Mate> mateViews = mates.stream()
                .filter(m -> !m.main())
                .map(m -> new Mate(m.name(), m.idCardMask(), false))
                .toList();
        return new AppointmentView(
                a.getAppointmentNo(),
                a.getStatus(),
                Appointment.statusText(a.getStatus()),
                a.getSource(),
                visitorName,
                visitorIdCardMask,
                a.getVisitorType(),
                a.getPurpose(),
                a.getVisitPhoneMask(),
                a.getDepartment(),
                a.getGateId(),
                gateName,
                a.getGateScope(),
                a.getGateScope() == Appointment.GATE_SCOPE_ALL ? "全部门点" : "指定门点",
                appliedGateNames == null ? List.of() : appliedGateNames,
                approvedGateNames == null ? List.of() : approvedGateNames,
                DATETIME.format(a.getStartTime()),
                DATETIME.format(a.getEndTime()),
                DATE.format(a.getStartTime()),
                a.getMateMode(),
                a.getMateMode() == Appointment.MATE_TOGETHER ? "随行一次放行" : "各自刷证",
                mateViews,
                a.getStatus() == Appointment.PENDING_L1 || a.getStatus() == Appointment.PENDING_L2,
                a.getCreatedAt(),
                a.getCompany(),
                a.getPosition(),
                Appointment.periodText(a.getPeriod()),
                a.getPlateNo(),
                a.getMateCount());
    }
}
