package com.fangke.visitor.qr.web;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.qr.service.QrTicketService;
import com.fangke.visitor.security.AppAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 访客端/员工端取码（docs/11 §2.4）：H5 首页“我的通行码”，60 秒自动过期，可重复刷新。
 *
 * <p>归属校验：访客只能取自己的预约；员工只能取“被访人是我”的预约。
 */
@RestController
@RequestMapping("/api/app/appointment")
public class QrAppController {

    private final QrTicketService service;
    private final AppointmentRepository appointmentRepository;

    public QrAppController(QrTicketService service, AppointmentRepository appointmentRepository) {
        this.service = service;
        this.appointmentRepository = appointmentRepository;
    }

    @PostMapping("/{appointmentNo}/qr")
    public ApiResponse<Map<String, Object>> issue(@PathVariable String appointmentNo,
                                                  HttpServletRequest request) {
        Appointment appointment = appointmentRepository.findByNo(appointmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "预约单不存在：" + appointmentNo));
        Long subjectId = AppAuthInterceptor.subjectId(request);
        boolean owner = Objects.equals(appointment.getVisitorId(), subjectId)
                || Objects.equals(appointment.getVisitEmployeeId(), subjectId);
        if (!owner) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能查看自己的通行码");
        }
        QrTicketService.IssueResult result =
                service.issue(appointmentNo, subjectId, QrTicketService.CHANNEL_APP);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", result.code());
        view.put("appointmentNo", result.appointmentNo());
        view.put("expireAt", result.expireAt());
        view.put("expireAtEpoch", result.expireAtEpoch());
        view.put("ttlSeconds", result.ttlSeconds());
        view.put("qrImage", result.qrImage());
        return ApiResponse.ok(view);
    }
}
