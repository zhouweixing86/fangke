package com.fangke.visitor.qr.web;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.qr.service.QrTicketService;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保安端动态二维码（docs/11 §5.10）：现场给访客发码、核验放行。
 *
 * <p>设备支持扫码前，保安用手机/PAD 打开工作台，扫码或手输码即可核验；
 * 换新机后设备走 {@code /api/device/callback/compare/online}，两条入口共用同一套签名与授权判定。
 */
@RestController
@RequestMapping("/api/guard")
public class QrGuardController {

    public record VerifyRequest(String code) {
    }

    private final QrTicketService service;

    public QrGuardController(QrTicketService service) {
        this.service = service;
    }

    /** 现场签发（现场自助登记通过后，保安直接给访客出码）。 */
    @PostMapping("/appointment/{appointmentNo}/qr")
    @AuditLog(module = "QR", action = "ISSUE_GUARD")
    public ApiResponse<Map<String, Object>> issue(@PathVariable String appointmentNo,
                                                  HttpServletRequest request) {
        RoleGuard.requireGuardOrAdmin(request);
        QrTicketService.IssueResult result =
                service.issue(appointmentNo, RoleGuard.currentUserId(request), QrTicketService.CHANNEL_GUARD);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", result.code());
        view.put("appointmentNo", result.appointmentNo());
        view.put("expireAt", result.expireAt());
        view.put("expireAtEpoch", result.expireAtEpoch());
        view.put("ttlSeconds", result.ttlSeconds());
        view.put("qrImage", result.qrImage());
        return ApiResponse.ok(view);
    }

    /** 扫码/手输码核验：验签 → 查票 → 授权 → 一次性消费 → 写通行记录与签到。 */
    @PostMapping("/qr/verify")
    @AuditLog(module = "QR", action = "VERIFY")
    public ApiResponse<Map<String, Object>> verify(@RequestBody VerifyRequest body,
                                                   HttpServletRequest request) {
        RoleGuard.requireGuardOrAdmin(request);
        if (body == null || body.code() == null || body.code().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请提供二维码内容");
        }
        QrTicketService.VerifyResult result = service.verify(body.code().trim(),
                RoleGuard.currentUserId(request), null);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("allowed", result.allowed());
        view.put("reason", result.reason());
        view.put("appointmentNo", result.appointmentNo());
        view.put("visitorName", result.visitorName());
        view.put("idCardMask", result.idCardMask());
        view.put("visitorType", result.visitorType());
        view.put("purpose", result.purpose());
        view.put("employeeName", result.employeeName());
        view.put("validFrom", result.validFrom());
        view.put("validTo", result.validTo());
        view.put("useCount", result.useCount());
        return ApiResponse.ok(view);
    }
}
