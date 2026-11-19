package com.fangke.visitor.notify.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.notify.model.Notification;
import com.fangke.visitor.notify.repository.NotificationRepository;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端通知记录（docs/11 §5.11）：只读，排查"用户说没收到消息"这类问题。
 */
@RestController
@RequestMapping("/api/admin/notification")
public class AdminNotifyController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationRepository repository;

    public AdminNotifyController(NotificationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String templateCode,
            @RequestParam(required = false) Integer receiverType,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = repository
                .listForAdmin(templateCode, receiverType, status, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        long total = repository.countForAdmin(templateCode, receiverType, status);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, list));
    }

    private Map<String, Object> toView(Notification n) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", n.id());
        view.put("receiverType", n.receiverType());
        view.put("receiverTypeText", n.receiverType() == Notification.RECEIVER_EMPLOYEE ? "员工" : "访客");
        view.put("receiverId", n.receiverId());
        view.put("channel", n.channel());
        view.put("channelText", n.channel() == Notification.CHANNEL_INAPP ? "站内" : "短信");
        view.put("templateCode", n.templateCode());
        view.put("title", n.title());
        view.put("content", n.content());
        view.put("bizNo", n.bizNo() == null ? "" : n.bizNo());
        view.put("status", n.status());
        view.put("statusText", switch (n.status()) {
            case Notification.STATUS_PENDING -> "待发";
            case Notification.STATUS_SENT -> "已发";
            case Notification.STATUS_FAILED -> "失败";
            default -> "未知";
        });
        view.put("read", n.read());
        view.put("retryCount", n.retryCount());
        view.put("errorMsg", n.errorMsg() == null ? "" : n.errorMsg());
        view.put("createdAt", n.createdAt() == null ? "" : DATETIME.format(n.createdAt()));
        return view;
    }
}
