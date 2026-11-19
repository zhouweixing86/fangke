package com.fangke.visitor.appointment.repository;

import java.time.LocalDateTime;

/**
 * 黑名单查询（docs/10 §5 blacklist）。按 HMAC 等值查询，不解密。
 */
public interface BlacklistRepository {

    int TARGET_ID_CARD = 1;
    int TARGET_PHONE = 2;

    boolean isBlacklisted(int targetType, String targetHmac, LocalDateTime at);
}
