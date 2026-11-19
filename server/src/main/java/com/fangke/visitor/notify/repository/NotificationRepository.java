package com.fangke.visitor.notify.repository;

import com.fangke.visitor.notify.model.Notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 通知仓储。{@link #insertIfAbsent} 依赖 {@code dedup_key} 唯一索引做幂等：
 * 同一次业务事件重复触发时不会给用户发两条一样的消息。
 */
public interface NotificationRepository {

    /**
     * @return 新插入的记录 id；命中 {@code dedup_key} 重复时返回 {@code null}
     */
    Long insertIfAbsent(Notification notification);

    Optional<Notification> findById(Long id);

    List<Notification> list(int receiverType, Long receiverId, Boolean unreadOnly, int offset, int limit);

    long count(int receiverType, Long receiverId, Boolean unreadOnly);

    long unreadCount(int receiverType, Long receiverId);

    int markRead(Long id, int receiverType, Long receiverId, LocalDateTime at);

    int markAllRead(int receiverType, Long receiverId, LocalDateTime at);

    void markSent(Long id, LocalDateTime at);

    void markFailed(Long id, String errorMsg);

    List<Notification> findRetryable(int limit);

    List<Notification> listForAdmin(String templateCode, Integer receiverType, Integer status,
                                    int offset, int limit);

    long countForAdmin(String templateCode, Integer receiverType, Integer status);
}
