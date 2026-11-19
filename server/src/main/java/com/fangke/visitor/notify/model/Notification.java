package com.fangke.visitor.notify.model;

import java.time.LocalDateTime;

/**
 * 通知（docs/10 notification）：站内消息与短信共用一张表，靠 channel 区分。
 */
public record Notification(Long id,
                           int receiverType,
                           Long receiverId,
                           int channel,
                           String templateCode,
                           String title,
                           String content,
                           String bizType,
                           String bizNo,
                           int status,
                           LocalDateTime readAt,
                           LocalDateTime sentAt,
                           int retryCount,
                           String errorMsg,
                           String dedupKey,
                           LocalDateTime createdAt) {

    public static final int RECEIVER_EMPLOYEE = 1;
    public static final int RECEIVER_VISITOR = 2;

    public static final int CHANNEL_INAPP = 1;
    public static final int CHANNEL_SMS = 2;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_FAILED = 2;

    public boolean read() {
        return readAt != null;
    }

    /** 最多重试 3 次（避免短信通道异常时无限重试）。 */
    public boolean canRetry() {
        return status == STATUS_FAILED && retryCount < 3;
    }
}
