package com.fangke.visitor.employee.dto;

import com.fangke.visitor.employee.model.Employee;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 员工列表/详情返回体：证件号与手机号只出脱敏值（BR-D-008）。
 */
public record EmployeeView(
        Long id,
        String personNo,
        String empNo,
        String name,
        Long deptId,
        String deptName,
        String title,
        String phoneMask,
        String idCardMask,
        int status,
        String statusText,
        LocalDate validFrom,
        LocalDate validTo,
        int faceStatus,
        String faceStatusText,
        boolean canPassByFace,
        String remark) {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static EmployeeView of(Employee e) {
        return new EmployeeView(
                e.getId(),
                e.getPersonNo(),
                e.getEmpNo(),
                e.getName(),
                e.getDeptId(),
                e.getDeptName(),
                e.getTitle(),
                e.getPhoneMask(),
                e.getIdCardMask(),
                e.getStatus(),
                statusText(e.getStatus()),
                e.getValidFrom(),
                e.getValidTo(),
                e.getFaceStatus(),
                faceStatusText(e.getFaceStatus()),
                e.canPassByFace(LocalDate.now()),
                e.getRemark());
    }

    private static String statusText(int status) {
        return switch (status) {
            case Employee.STATUS_ACTIVE -> "在职";
            case Employee.STATUS_PENDING_REVIEW -> "待复核";
            case Employee.STATUS_DISABLED -> "停用";
            default -> "未知";
        };
    }

    private static String faceStatusText(int faceStatus) {
        return switch (faceStatus) {
            case Employee.FACE_NONE -> "无照片";
            case Employee.FACE_UPLOADED -> "已上传";
            case Employee.FACE_SYNCING -> "下发中";
            case Employee.FACE_EFFECTIVE -> "已生效";
            default -> "未知";
        };
    }

    /** 便于日志/导出时格式化日期。 */
    public String validRange() {
        String from = validFrom == null ? "" : validFrom.format(D);
        String to = validTo == null ? "" : validTo.format(D);
        return from + "~" + to;
    }
}
