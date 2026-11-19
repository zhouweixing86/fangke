package com.fangke.visitor.admin.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 黑名单管理端查询/写入（docs/10 §5 blacklist）。
 *
 * <p>与 {@code BlacklistRepository} 的分工：那个只做“命中判定”（按 HMAC 等值查、不解密），
 * 这个给后台做列表/新增/失效，写入时加密 + 摘要 + 脱敏三件套一起落库。
 */
public interface AdminBlacklistRepository {

    record Row(Long id,
               int targetType,
               String targetMask,
               String reason,
               LocalDateTime validFrom,
               LocalDateTime validTo,
               int status,
               Long createdBy,
               LocalDateTime createdAt) {
    }

    List<Row> search(String keyword, Integer status, int offset, int limit);

    long count(String keyword, Integer status);

    Optional<Row> findById(Long id);

    /** 取拉黑对象的 HMAC（用于反查访客档案），不对外暴露。 */
    Optional<String> findTargetHmac(Long id);

    /** 同类型 + 同 HMAC 且仍在有效期内，说明重复拉黑。 */
    boolean existsEffective(int targetType, String targetHmac);

    long insert(int targetType, byte[] targetEnc, String targetHmac, String targetMask,
                String reason, LocalDateTime validFrom, LocalDateTime validTo, Long createdBy);

    void updateStatus(Long id, int status);
}
