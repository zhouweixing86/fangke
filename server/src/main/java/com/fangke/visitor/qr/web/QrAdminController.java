package com.fangke.visitor.qr.web;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.qr.service.QrTicketService;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端动态二维码（docs/11 §5.10）：给预约单签发 / 查看 / 撤销通行码。
 *
 * <p>后台与客服都在这里操作；访客端（小程序/H5）走 M10 再开放自助刷新。
 */
@RestController
@RequestMapping("/api/admin/appointment")
public class QrAdminController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QrTicketService service;

    public QrAdminController(QrTicketService service) {
        this.service = service;
    }

    /** 签发一张 60 秒通行码（只有已授权且在有效期内的预约可签发）。 */
    @PostMapping("/{appointmentNo}/qr")
    @AuditLog(module = "QR", action = "ISSUE")
    public ApiResponse<Map<String, Object>> issue(@PathVariable String appointmentNo,
                                                  HttpServletRequest request) {
        QrTicketService.IssueResult result =
                service.issue(appointmentNo, RoleGuard.currentUserId(request), QrTicketService.CHANNEL_ADMIN);
        return ApiResponse.ok(toView(result));
    }

    /** 查看当前有效票（不返回明文码，只给有效期与已用次数）。 */
    @GetMapping("/{appointmentNo}/qr")
    public ApiResponse<Map<String, Object>> current(@PathVariable String appointmentNo) {
        return ApiResponse.ok(service.findLatestValid(appointmentNo)
                .map(ticket -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("expireAt", DATETIME.format(ticket.expireAt()));
                    view.put("status", ticket.status());
                    view.put("statusText", com.fangke.visitor.qr.model.QrTicket.statusText(ticket.status()));
                    view.put("useCount", ticket.useCount());
                    return view;
                })
                .orElse(null));
    }

    /** 撤销该预约下所有未使用的码（撤回预约、拉黑、撤销授权时使用）。 */
    @PostMapping("/{appointmentNo}/qr/revoke")
    @AuditLog(module = "QR", action = "REVOKE")
    public ApiResponse<Map<String, Object>> revoke(@PathVariable String appointmentNo) {
        int count = service.revoke(appointmentNo);
        return ApiResponse.ok(Map.of("revoked", count));
    }

    private Map<String, Object> toView(QrTicketService.IssueResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", result.code());
        view.put("appointmentNo", result.appointmentNo());
        view.put("expireAt", result.expireAt());
        view.put("expireAtEpoch", result.expireAtEpoch());
        view.put("ttlSeconds", result.ttlSeconds());
        view.put("channel", result.channel());
        view.put("qrImage", result.qrImage());
        return view;
    }
}
