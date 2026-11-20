package com.fangke.visitor.dispatch.web;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.dispatch.model.SyncBatch;
import com.fangke.visitor.dispatch.model.SyncBatchItem;
import com.fangke.visitor.dispatch.repository.AccessGrantRepository;
import com.fangke.visitor.dispatch.repository.SyncBatchRepository;
import com.fangke.visitor.dispatch.service.DispatchService;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.gate.repository.GateRepository;
import com.fangke.visitor.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权下发管理（docs/11 §5.5）：批次查询、批次重试、授权查询、手动重下、撤销授权。
 */
@RestController
@RequestMapping("/api/admin")
public class DispatchAdminController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DispatchService dispatchService;
    private final SyncBatchRepository batchRepository;
    private final AppointmentRepository appointmentRepository;
    private final com.fangke.visitor.qr.service.QrTicketService qrTicketService;
    private final AccessGrantRepository grantRepository;
    private final GateRepository gateRepository;
    private final DeviceRepository deviceRepository;

    public DispatchAdminController(DispatchService dispatchService,
                                   SyncBatchRepository batchRepository,
                                   AppointmentRepository appointmentRepository,
                                   com.fangke.visitor.qr.service.QrTicketService qrTicketService,
                                   AccessGrantRepository grantRepository,
                                   GateRepository gateRepository,
                                   DeviceRepository deviceRepository) {
        this.dispatchService = dispatchService;
        this.batchRepository = batchRepository;
        this.appointmentRepository = appointmentRepository;
        this.qrTicketService = qrTicketService;
        this.grantRepository = grantRepository;
        this.gateRepository = gateRepository;
        this.deviceRepository = deviceRepository;
    }

    @GetMapping("/sync/batch")
    public ApiResponse<PageResult<Map<String, Object>>> batches(@RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = batchRepository.list((safePage - 1) * safeSize, safeSize)
                .stream().map(this::batchView).toList();
        return ApiResponse.ok(PageResult.of(batchRepository.count(), safePage, safeSize, list));
    }

    @GetMapping("/sync/batch/{id}/items")
    public ApiResponse<PageResult<Map<String, Object>>> items(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        List<Map<String, Object>> list = batchRepository.items(id, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::itemView).toList();
        return ApiResponse.ok(PageResult.of(batchRepository.countItems(id), safePage, safeSize, list));
    }

    /** 批次列表视图：把 gate_id 翻成门点名，页面不显示裸 ID。 */
    private Map<String, Object> batchView(SyncBatch batch) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", batch.id());
        view.put("batchNo", batch.batchNo());
        view.put("triggerType", batch.triggerType());
        view.put("gateId", batch.gateId() == null ? 0 : batch.gateId());
        view.put("gateName", gateName(batch.gateId()));
        view.put("totalCount", batch.totalCount());
        view.put("successCount", batch.successCount());
        view.put("failCount", batch.failCount());
        view.put("status", batch.status());
        view.put("startedAt", datetime(batch.startedAt()));
        view.put("finishedAt", datetime(batch.finishedAt()));
        return view;
    }

    /** 批次明细视图：补设备名与 SN，运维排查时不用再查设备表。 */
    private Map<String, Object> itemView(SyncBatchItem item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.id());
        view.put("batchId", item.batchId());
        view.put("deviceId", item.deviceId());
        view.put("deviceName", deviceRepository.findById(item.deviceId())
                .map(d -> d.getDeviceName() + "（" + d.getDeviceSn() + "）").orElse("-"));
        view.put("personNo", item.personNo());
        view.put("action", item.action());
        view.put("status", item.status());
        view.put("errorCode", item.errorCode());
        view.put("errorMsg", item.errorMsg());
        view.put("ackedAt", datetime(item.ackedAt()));
        return view;
    }

    private String gateName(Long gateId) {
        if (gateId == null || gateId <= 0) {
            return "-";
        }
        return gateRepository.findById(gateId).map(g -> g.gateName()).orElse("-");
    }

    private String datetime(LocalDateTime value) {
        return value == null ? "" : DATETIME.format(value);
    }

    /** 重试某批次里失败的人员（按 personNo 反查预约后重新手动下发）。 */
    @PostMapping("/sync/batch/{id}/retry")
    @AuditLog(module = "DISPATCH", action = "RETRY_BATCH")
    public ApiResponse<DispatchService.RetryResult> retryBatch(@PathVariable Long id) {
        return ApiResponse.ok(dispatchService.retryBatch(id));
    }

    /** 授权查询：排查"谁能进门、什么时候过期"。 */
    @GetMapping("/grant")
    public ApiResponse<PageResult<Map<String, Object>>> grants(
            @RequestParam(required = false) Integer subjectType,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = grantRepository
                .search(subjectType, status, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        return ApiResponse.ok(PageResult.of(grantRepository.count(subjectType, status),
                safePage, safeSize, list));
    }

    private Map<String, Object> toView(com.fangke.visitor.dispatch.model.AccessGrant g) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", g.id());
        view.put("subjectType", g.subjectType());
        view.put("subjectTypeText", g.subjectType() == 1 ? "员工" : "访客");
        view.put("subjectId", g.subjectId());
        view.put("personNo", g.personNo());
        view.put("appointmentId", g.appointmentId() == null ? 0 : g.appointmentId());
        view.put("gateId", g.gateId());
        view.put("validFrom", g.validFrom() == null ? "" : DATETIME.format(g.validFrom()));
        view.put("validTo", g.validTo() == null ? "" : DATETIME.format(g.validTo()));
        view.put("status", g.status());
        view.put("statusText", switch (g.status()) {
            case 1 -> "生效";
            case 2 -> "待下发";
            case 3 -> "下发中";
            case 4 -> "已撤销";
            case 5 -> "已过期";
            default -> "未知";
        });
        return view;
    }

    /** 手动下发/重下（终审通过后自动下发失败时使用）。 */    @PostMapping("/dispatch/{appointmentNo}")
    @AuditLog(module = "DISPATCH", action = "MANUAL_DISPATCH")
    public ApiResponse<DispatchService.DispatchResult> dispatch(@PathVariable String appointmentNo) {
        Appointment appointment = appointmentRepository.findByNo(appointmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "预约单不存在：" + appointmentNo));
        return ApiResponse.ok(dispatchService.dispatch(appointment.getId(), SyncBatch.TRIGGER_MANUAL));
    }

    /** 撤销授权：设备名单删除 + 授权置撤销 + 预约单置撤销。 */
    @PostMapping("/grant/{appointmentNo}/revoke")
    @AuditLog(module = "DISPATCH", action = "REVOKE")
    public ApiResponse<DispatchService.DispatchResult> revoke(@PathVariable String appointmentNo,
                                                              HttpServletRequest request) {
        Appointment appointment = appointmentRepository.findByNo(appointmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "预约单不存在：" + appointmentNo));
        Object uid = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        Long operatorId = uid instanceof Long l ? l : null;
        // 撤销授权的同时把未使用的通行码作废，避免访客继续用码进门
        qrTicketService.revoke(appointmentNo);
        return ApiResponse.ok(dispatchService.revoke(appointment.getId(), operatorId));
    }
}
