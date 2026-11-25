package com.fangke.visitor.admin.web;

import com.fangke.visitor.admin.repository.AdminReportRepository;
import com.fangke.visitor.admin.service.ReportService;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表统计（docs/11 §5.8）：概览卡片 + 逐日明细。只读，ADMIN 专属。
 */
@RestController
@RequestMapping("/api/admin/report")
public class AdminReportController {

    private final ReportService service;

    public AdminReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        ReportService.Range range = service.resolveRange(from, to);
        AdminReportRepository.Overview data = service.overview(from, to);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("from", range.from().toString());
        view.put("to", range.to().toString());
        view.put("appointments", data.appointments());
        view.put("approved", data.approved());
        view.put("rejected", data.rejected());
        view.put("pending", data.pending());
        view.put("withdrawn", data.withdrawn());
        view.put("checkins", data.checkins());
        view.put("onsiteNow", data.onsiteNow());
        view.put("events", data.events());
        view.put("eventPass", data.eventPass());
        view.put("eventFail", data.eventFail());
        view.put("avgApprovalMinutes", data.avgApprovalMinutes() == null
                ? 0 : Math.round(data.avgApprovalMinutes() * 10) / 10.0);
        view.put("approvalRate", rate(data.approved(), data.approved() + data.rejected()));
        view.put("eventPassRate", rate(data.eventPass(), data.events()));
        return ApiResponse.ok(view);
    }

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> daily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        ReportService.Range range = service.resolveRange(from, to);
        List<Map<String, Object>> list = service.daily(from, to).stream().map(this::toView).toList();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("from", range.from().toString());
        view.put("to", range.to().toString());
        view.put("list", list);
        return ApiResponse.ok(view);
    }

    /** 访客维度：按部门、按访客类型。 */
    @GetMapping("/visitor")
    public ApiResponse<Map<String, Object>> visitor(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        ReportService.Range range = service.resolveRange(from, to);
        ReportService.VisitorReport report = service.visitorReport(from, to);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("from", range.from().toString());
        view.put("to", range.to().toString());
        view.put("byDept", report.byDept().stream().map(r -> row(r.key(), r.total(), r.approved(), r.arrived())).toList());
        view.put("byType", report.byType().stream().map(r -> row(r.key(), r.total(), r.approved(), r.arrived())).toList());
        view.put("columns", List.of("总数", "通过", "到访"));
        return ApiResponse.ok(view);
    }

    /** 通行维度：按设备、按识别方式。 */
    @GetMapping("/access")
    public ApiResponse<Map<String, Object>> access(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        ReportService.Range range = service.resolveRange(from, to);
        ReportService.AccessReport report = service.accessReport(from, to);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("from", range.from().toString());
        view.put("to", range.to().toString());
        view.put("byDevice", report.byDevice().stream().map(r -> row(r.key(), r.total(), r.pass(), r.fail())).toList());
        view.put("byIdentifyType", report.byIdentifyType().stream()
                .map(r -> row(identifyText(r.key()), r.total(), r.pass(), r.fail())).toList());
        view.put("columns", List.of("总数", "通过", "失败"));
        return ApiResponse.ok(view);
    }

    private Map<String, Object> row(String key, long total, long a, long b) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("total", total);
        row.put("a", a);
        row.put("b", b);
        return row;
    }

    private String identifyText(String code) {
        if (code == null) {
            return "未知";
        }
        return switch (code) {
            case "0" -> "人脸";
            case "2" -> "人证";
            case "3" -> "IC 卡";
            case "5" -> "二维码";
            case "9" -> "人工开门";
            case "20" -> "车牌识别";
            default -> "其他(" + code + ")";
        };
    }

    private Map<String, Object> toView(AdminReportRepository.Daily row) {        Map<String, Object> view = new LinkedHashMap<>();
        view.put("date", row.date().toString());
        view.put("appointments", row.appointments());
        view.put("checkins", row.checkins());
        view.put("eventPass", row.eventPass());
        view.put("eventFail", row.eventFail());
        return view;
    }

    /** 比例保留 4 位小数（0~1），分母为 0 时返回 0。 */
    private double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }
}
