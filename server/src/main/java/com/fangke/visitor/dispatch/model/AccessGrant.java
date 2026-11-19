package com.fangke.visitor.dispatch.model;

import java.time.LocalDateTime;

/**
 * 通行授权（docs/10 §7 access_grant）。
 *
 * <p>有效期按自然日整段（C-60）：预约当天 00:00:00 ~ 23:59:59。
 */
public record AccessGrant(
        Long id,
        int subjectType,
        Long subjectId,
        String personNo,
        Long appointmentId,
        Long gateId,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        int status) {

    public static final int SUBJECT_EMPLOYEE = 1;
    public static final int SUBJECT_VISITOR = 2;

    /** 1生效 2待下发 3下发中 4已撤销 5已过期。 */
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_PENDING = 2;
    public static final int STATUS_DISPATCHING = 3;
    public static final int STATUS_REVOKED = 4;
    public static final int STATUS_EXPIRED = 5;

    public AccessGrant withStatus(int newStatus) {
        return new AccessGrant(id, subjectType, subjectId, personNo, appointmentId, gateId,
                validFrom, validTo, newStatus);
    }
}
