package com.fangke.visitor.vehicle.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.vehicle.model.VehicleEvent;
import com.fangke.visitor.vehicle.repository.VehicleEventRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端车辆通行记录（docs/11 §5.12）。
 */
@RestController
@RequestMapping("/api/admin/vehicle")
public class AdminVehicleController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VehicleEventRepository eventRepository;
    private final GateService gateService;

    public AdminVehicleController(VehicleEventRepository eventRepository, GateService gateService) {
        this.eventRepository = eventRepository;
        this.gateService = gateService;
    }

    @GetMapping("/event")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String plateNo,
            @RequestParam(required = false) Long gateId,
            @RequestParam(required = false) Integer result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = eventRepository
                .search(from, to, plateNo, gateId, result, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        long total = eventRepository.count(from, to, plateNo, gateId, result);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, list));
    }

    private Map<String, Object> toView(VehicleEvent e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", e.id());
        view.put("plateNo", e.plateNo());
        view.put("gateId", e.gateId() == null ? 0L : e.gateId());
        view.put("gateName", gateService.nameOf(e.gateId()));
        view.put("direction", e.direction());
        view.put("directionText", e.direction() == 0 ? "出场" : "进场");
        view.put("eventTime", DATETIME.format(e.eventTime()));
        view.put("confidence", e.confidence() == null ? 0 : e.confidence());
        view.put("matchedType", e.matchedType());
        view.put("matchedTypeText", VehicleEvent.typeText(e.matchedType()));
        view.put("matchedName", e.matchedName() == null ? "" : e.matchedName());
        view.put("result", e.resultStatus());
        view.put("resultText", e.resultStatus() == VehicleEvent.RESULT_PASS ? "放行" : "拒绝");
        view.put("failReason", e.failReason() == null ? "" : e.failReason());
        view.put("photoKey", e.photoKey() == null ? "" : e.photoKey());
        view.put("deviceSn", e.deviceSn() == null ? "" : e.deviceSn());
        return view;
    }
}
