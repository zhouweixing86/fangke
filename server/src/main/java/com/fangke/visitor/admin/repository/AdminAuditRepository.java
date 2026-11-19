package com.fangke.visitor.admin.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志查询（docs/10 §11 audit_log，只读、不可删除）。
 */
public interface AdminAuditRepository {

    record Row(Long id,
               Long userId,
               String userName,
               String module,
               String action,
               String target,
               String requestUri,
               String requestIp,
               String detail,
               int result,
               LocalDateTime createdAt) {
    }

    List<Row> search(LocalDate from, LocalDate to, String module, String action,
                     String keyword, Long userId, int offset, int limit);

    long count(LocalDate from, LocalDate to, String module, String action, String keyword, Long userId);

    List<String> modules();

    List<String> actions();
}
