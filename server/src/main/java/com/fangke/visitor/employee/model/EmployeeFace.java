package com.fangke.visitor.employee.model;

import java.time.LocalDateTime;

/**
 * 员工人脸照片元信息（docs/10 §3 employee_face）。二进制在对象存储。
 */
public record EmployeeFace(
        Long id,
        Long employeeId,
        String ossKey,
        String ossKeyStd,
        Integer width,
        Integer height,
        Integer sizeBytes,
        String sha256,
        boolean current,
        Long uploadedBy,
        LocalDateTime createdAt
) {
}
