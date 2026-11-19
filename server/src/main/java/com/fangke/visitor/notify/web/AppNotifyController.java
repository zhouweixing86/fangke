package com.fangke.visitor.notify.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.notify.model.Notification;
import com.fangke.visitor.notify.service.NotifyService;
import com.fangke.visitor.security.AppAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动端消息中心（docs/11 §5.11）：员工与访客共用，按 token 身份取自己的消息。
 */
@RestController
@RequestMapping("/api/app/notifications")
public class AppNotifyController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NotifyService notifyService;

    public AppNotifyController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        int receiverType = receiverType(request);
        Long receiverId = AppAuthInterceptor.subjectId(request);
        PageResult<Notification> result = notifyService.list(receiverType, receiverId, unreadOnly, page, size);
        List<Map<String, Object>> list = result.getList().stream().map(this::toView).toList();
        return ApiResponse.ok(PageResult.of(result.getTotal(), result.getPage(), result.getSize(), list));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(HttpServletRequest request) {
        long count = notifyService.unreadCount(receiverType(request), AppAuthInterceptor.subjectId(request));
        return ApiResponse.ok(Map.of("unread", count));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id, HttpServletRequest request) {
        notifyService.markRead(receiverType(request), AppAuthInterceptor.subjectId(request), id);
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(HttpServletRequest request) {
        int count = notifyService.markAllRead(receiverType(request), AppAuthInterceptor.subjectId(request));
        return ApiResponse.ok(Map.of("updated", count));
    }

    private int receiverType(HttpServletRequest request) {
        return AppAuthInterceptor.isEmployee(request)
                ? Notification.RECEIVER_EMPLOYEE
                : Notification.RECEIVER_VISITOR;
    }

    private Map<String, Object> toView(Notification n) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", n.id());
        view.put("templateCode", n.templateCode());
        view.put("title", n.title());
        view.put("content", n.content());
        view.put("bizType", n.bizType() == null ? "" : n.bizType());
        view.put("bizNo", n.bizNo() == null ? "" : n.bizNo());
        view.put("read", n.read());
        view.put("readAt", n.readAt() == null ? "" : DATETIME.format(n.readAt()));
        view.put("createdAt", n.createdAt() == null ? "" : DATETIME.format(n.createdAt()));
        return view;
    }
}
