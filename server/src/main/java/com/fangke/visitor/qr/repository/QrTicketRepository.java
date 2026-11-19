package com.fangke.visitor.qr.repository;

import com.fangke.visitor.qr.model.QrTicket;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 二维码票据仓储。
 *
 * <p>注意：码在 60 秒内**可重复使用**（BR-V-020），所以这里没有“消费”语义，
 * 只有 {@link #recordUse} 累加使用次数。
 */
public interface QrTicketRepository {

    long insert(QrTicket ticket);

    Optional<QrTicket> findByNonce(String nonce);

    /** 记录一次核验放行：use_count + 1，刷新 last_used_at / verified_by。 */
    int recordUse(Long id, Long verifiedBy, LocalDateTime at);

    /** 撤销某预约下所有有效票，返回影响行数。 */
    int revokeByAppointment(Long appointmentId);

    List<QrTicket> findValidByAppointment(Long appointmentId);

    /** 定时任务用：把过期未处理的票置为已过期。 */
    int markExpired(LocalDateTime now);

    /** 定时任务用：删除 N 天前的历史票（保留期见 docs/10 §12）。 */
    int deleteBefore(LocalDateTime time);
}
