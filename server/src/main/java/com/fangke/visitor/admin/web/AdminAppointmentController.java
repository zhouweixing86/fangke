package com.fangke.visitor.admin.web;

import com.fangke.visitor.admin.repository.AdminQueryRepository;
import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.gate.service.GateService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端预约查询（docs/11 §5.6）。
 */
@RestController
@RequestMapping("/api/admin/appointment")
public class AdminAppointmentController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AdminQueryRepository queryRepository;
    private final GateService gateService;

    public AdminAppointmentController(AdminQueryRepository queryRepository, GateService gateService) {
        this.queryRepository = queryRepository;
        this.gateService = gateService;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = queryRepository
                .searchAppointments(keyword, status, from, to, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        long total = queryRepository.countAppointments(keyword, status, from, to);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, list));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(queryRepository.findAppointment(id)
                .map(this::toView)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "预约单不存在：" + id)));
    }

    private Map<String, Object> toView(AdminQueryRepository.AppointmentRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("appointmentNo", row.appointmentNo());
        view.put("visitorName", row.visitorName() == null ? "" : row.visitorName());
        view.put("idCardMask", row.idCardMask() == null ? "" : row.idCardMask());
        view.put("visitorType", row.visitorType() == null ? "" : row.visitorType());
        view.put("purpose", row.purpose() == null ? "" : row.purpose());
        view.put("startTime", DATETIME.format(row.startTime()));
        view.put("endTime", DATETIME.format(row.endTime()));
        view.put("status", row.status());
        view.put("statusText", Appointment.statusText(row.status()));
        view.put("source", row.source());
        view.put("sourceText", sourceText(row.source()));
        // 展示实际生效门点：历史单据没写门点时按默认门点显示
        Long gateId = row.gateId() == null ? gateService.defaultGateId() : row.gateId();
        view.put("gateId", gateId);
        view.put("gateName", gateService.nameOf(gateId));
        view.put("gateScope", row.gateScope());
        view.put("gateScopeText", row.gateScope() == Appointment.GATE_SCOPE_ALL ? "全部门点" : "指定门点");
        view.put("appliedGates", row.appliedGates() == null ? "" : row.appliedGates());
        view.put("approvedGates", row.approvedGates() == null ? "" : row.approvedGates());
        view.put("rejectReason", row.rejectReason() == null ? "" : row.rejectReason());
        view.put("grantStatus", row.grantStatus() == null ? -1 : row.grantStatus());
        view.put("canRevoke", row.grantStatus() != null && row.grantStatus() == 1);
        view.put("createdAt", row.createdAt() == null ? "" : DATETIME.format(row.createdAt()));
        return view;
    }

    private String sourceText(int source) {
        return switch (source) {
            case Appointment.SOURCE_SELF -> "访客自约";
            case Appointment.SOURCE_INVITE -> "员工代邀";
            case Appointment.SOURCE_ONSITE -> "现场自助登记";
            default -> "未知";
        };
    }
}
