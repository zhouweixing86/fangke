package com.fangke.visitor.qr.model;

import java.time.LocalDateTime;

/**
 * 动态二维码票据（docs/10 §9 credential 的落地表）。
 *
 * <p>与需求对齐的两条硬规则：
 * <ul>
 *   <li>BR-V-019：有效期 60 秒；</li>
 *   <li>BR-V-020：<b>60 秒内可重复使用</b>，通行后不作废（用 {@code useCount} 记录次数）。</li>
 * </ul>
 * 只存 {@code codeHash}（SHA256），不存明文码。
 */
public record QrTicket(Long id,
                       Long appointmentId,
                       String appointmentNo,
                       String nonce,
                       String codeHash,
                       int status,
                       int useCount,
                       String issuedChannel,
                       Long issuedBy,
                       LocalDateTime issuedAt,
                       LocalDateTime expireAt,
                       LocalDateTime lastUsedAt,
                       Long verifiedBy) {

    public static final int STATUS_VALID = 1;
    public static final int STATUS_EXPIRED = 2;
    public static final int STATUS_REVOKED = 3;

    public static String statusText(int status) {
        return switch (status) {
            case STATUS_VALID -> "有效";
            case STATUS_EXPIRED -> "已过期";
            case STATUS_REVOKED -> "已撤销";
            default -> "未知";
        };
    }
}
