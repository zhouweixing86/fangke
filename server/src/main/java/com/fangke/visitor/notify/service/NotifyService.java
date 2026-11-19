package com.fangke.visitor.notify.service;

import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.notify.model.Notification;
import com.fangke.visitor.notify.model.NotifyTemplates;
import com.fangke.visitor.notify.repository.NotificationRepository;
import com.fangke.visitor.notify.sms.SmsSender;
import com.fangke.visitor.security.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知中心（docs/11 §5.11）：站内消息 + 短信，统一落库、去重、可重试。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>先落库再发送</b>：短信通道失败时用户仍能在「消息」里看到内容；</li>
 *   <li><b>去重</b>：{@code dedup_key} 唯一索引，业务重试或重复触发不会刷屏；</li>
 *   <li><b>失败可重试</b>：短信失败置 {@code status=2}，定时任务重试，最多 3 次。</li>
 * </ul>
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    private final NotificationRepository repository;
    private final SmsSender smsSender;
    private final EmployeeRepository employeeRepository;
    private final VisitorRepository visitorRepository;
    private final CryptoService crypto;

    public NotifyService(NotificationRepository repository,
                         SmsSender smsSender,
                         EmployeeRepository employeeRepository,
                         VisitorRepository visitorRepository,
                         CryptoService crypto) {
        this.repository = repository;
        this.smsSender = smsSender;
        this.employeeRepository = employeeRepository;
        this.visitorRepository = visitorRepository;
        this.crypto = crypto;
    }

    /** 员工通知（站内 + 可选短信）。 */
    public boolean notifyEmployee(Long employeeId, String templateCode, NotifyTemplates.Content content,
                                  String bizType, String bizNo, String dedupSuffix, boolean withSms) {
        return notify(Notification.RECEIVER_EMPLOYEE, employeeId, templateCode, content,
                bizType, bizNo, dedupSuffix, withSms);
    }

    /** 访客通知（站内 + 可选短信）。 */
    public boolean notifyVisitor(Long visitorId, String templateCode, NotifyTemplates.Content content,
                                 String bizType, String bizNo, String dedupSuffix, boolean withSms) {
        return notify(Notification.RECEIVER_VISITOR, visitorId, templateCode, content,
                bizType, bizNo, dedupSuffix, withSms);
    }

    /**
     * @return 站内消息是否新插入（false 表示已存在同内容通知，被去重）
     */
    public boolean notify(int receiverType, Long receiverId, String templateCode,
                          NotifyTemplates.Content content, String bizType, String bizNo,
                          String dedupSuffix, boolean withSms) {
        String key = dedupKey(templateCode, receiverType, receiverId, bizNo, dedupSuffix);
        Long inAppId = repository.insertIfAbsent(new Notification(null, receiverType, receiverId,
                Notification.CHANNEL_INAPP, templateCode, content.title(), content.content(),
                bizType, bizNo, Notification.STATUS_SENT, null, LocalDateTime.now(), 0, null, key, null));

        if (withSms) {
            sendSms(receiverType, receiverId, templateCode, content, bizType, bizNo, key);
        }
        return inAppId != null;
    }

    public PageResult<Notification> list(int receiverType, Long receiverId, Boolean unreadOnly,
                                        int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        List<Notification> list = repository.list(receiverType, receiverId, unreadOnly,
                (safePage - 1) * safeSize, safeSize);
        return PageResult.of(repository.count(receiverType, receiverId, unreadOnly),
                safePage, safeSize, list);
    }

    public long unreadCount(int receiverType, Long receiverId) {
        return repository.unreadCount(receiverType, receiverId);
    }

    public boolean markRead(int receiverType, Long receiverId, Long id) {
        return repository.markRead(id, receiverType, receiverId, LocalDateTime.now()) > 0;
    }

    public int markAllRead(int receiverType, Long receiverId) {
        return repository.markAllRead(receiverType, receiverId, LocalDateTime.now());
    }

    /** 定时任务：重试失败的短信通知。 */
    public int retryFailed(int limit) {
        List<Notification> failed = repository.findRetryable(limit);
        int success = 0;
        for (Notification n : failed) {
            String phone = resolvePhone(n.receiverType(), n.receiverId());
            if (phone == null) {
                repository.markFailed(n.id(), "接收人手机号缺失");
                continue;
            }
            try {
                if (smsSender.send(phone, n.content())) {
                    repository.markSent(n.id(), LocalDateTime.now());
                    success++;
                } else {
                    repository.markFailed(n.id(), "短信通道返回失败：" + smsSender.provider());
                }
            } catch (Exception e) {
                repository.markFailed(n.id(), e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            log.info("短信重试：共 {} 条，成功 {} 条", failed.size(), success);
        }
        return success;
    }

    /* ---------------- 内部 ---------------- */

    private void sendSms(int receiverType, Long receiverId, String templateCode,
                         NotifyTemplates.Content content, String bizType, String bizNo, String key) {
        String phone = resolvePhone(receiverType, receiverId);
        if (phone == null) {
            log.debug("接收人无手机号，跳过短信 receiver={}:{}", receiverType, receiverId);
            return;
        }
        // 短信与站内消息共用 dedup_key 会互相覆盖，这里加通道后缀
        Long id = repository.insertIfAbsent(new Notification(null, receiverType, receiverId,
                Notification.CHANNEL_SMS, templateCode, content.title(), content.content(),
                bizType, bizNo, Notification.STATUS_PENDING, null, null, 0, null,
                key + ":SMS", null));
        if (id == null) {
            return;
        }
        try {
            if (smsSender.send(phone, content.content())) {
                repository.markSent(id, LocalDateTime.now());
            } else {
                repository.markFailed(id, "短信通道返回失败：" + smsSender.provider());
            }
        } catch (Exception e) {
            repository.markFailed(id, e.getMessage());
            log.warn("短信发送异常 receiver={}:{}：{}", receiverType, receiverId, e.getMessage());
        }
    }

    private String resolvePhone(int receiverType, Long receiverId) {
        try {
            if (receiverType == Notification.RECEIVER_EMPLOYEE) {
                return employeeRepository.findById(receiverId)
                        .map(e -> e.getPhoneEnc() == null ? null : crypto.decrypt(e.getPhoneEnc()))
                        .orElse(null);
            }
            return visitorRepository.findById(receiverId)
                    .map(v -> v.getPhoneEnc() == null ? null : crypto.decrypt(v.getPhoneEnc()))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("解析手机号失败 receiver={}:{}：{}", receiverType, receiverId, e.getMessage());
            return null;
        }
    }

    private String dedupKey(String templateCode, int receiverType, Long receiverId,
                            String bizNo, String suffix) {
        String key = templateCode + ":" + receiverType + ":" + receiverId + ":"
                + (bizNo == null ? "-" : bizNo);
        return suffix == null || suffix.isBlank() ? key : key + ":" + suffix;
    }
}
