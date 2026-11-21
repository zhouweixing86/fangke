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

    /**
     * 取拉黑对象的密文（下发到设备需要明文证件号，由调用方解密）。
     *
     * <p>默认返回空，方便测试替身只实现自己关心的方法。
     */
    default Optional<byte[]> findTargetEnc(Long id) {
        return Optional.empty();
    }

    /**
     * 当前生效的身份证类型黑名单密文列表。
     *
     * <p>设备开机拉全量名单索引（{@code getAllDeviceWhiteList}）时，黑名单也必须出现在索引里，
     * 否则设备可能把本地黑名单当成"平台已删除"而清掉。
     *
     * <p>默认返回空，方便测试替身只实现自己关心的方法。
     */
    default List<byte[]> findActiveIdCardTargets() {
        return List.of();
    }

    /** 同类型 + 同 HMAC 且仍在有效期内，说明重复拉黑。 */
    boolean existsEffective(int targetType, String targetHmac);

    long insert(int targetType, byte[] targetEnc, String targetHmac, String targetMask,
                String reason, LocalDateTime validFrom, LocalDateTime validTo, Long createdBy);

    void updateStatus(Long id, int status);
}
