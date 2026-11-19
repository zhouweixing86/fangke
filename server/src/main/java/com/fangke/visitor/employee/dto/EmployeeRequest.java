package com.fangke.visitor.employee.dto;

import java.time.LocalDate;

/**
 * 员工新增/编辑请求。证件号与手机号传明文，服务端加密后落库。
 */
public record EmployeeRequest(
        String empNo,
        String name,
        Long deptId,
        Long leaderId,
        String title,
        String phone,
        String idCard,
        LocalDate validFrom,
        LocalDate validTo,
        String remark) {
}
