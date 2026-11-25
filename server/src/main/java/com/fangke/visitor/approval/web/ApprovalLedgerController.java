package com.fangke.visitor.approval.web;

import com.fangke.visitor.admin.repository.AdminQueryRepository;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.gate.service.GateService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批台账（C-80）：管理端查看全部审批动作（谁、什么时候、在哪个节点、通过还是驳回、意见、门点），
 * 支持按时间/结果/审批人/单号筛选与导出，用于流程追溯与履职核对。
 */
@RestController
@RequestMapping("/api/admin/approval/records")
public class ApprovalLedgerController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AdminQueryRepository queryRepository;
    private final GateService gateService;

    public ApprovalLedgerController(AdminQueryRepository queryRepository, GateService gateService) {
        this.queryRepository = queryRepository;
        this.gateService = gateService;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = queryRepository
                .searchApprovalRecords(from, to, action, operator, keyword, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        long total = queryRepository.countApprovalRecords(from, to, action, operator, keyword);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, list));
    }

    /** 导出 CSV（UTF-8 BOM，Excel 直接打开），最多 5000 条。 */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            HttpServletResponse response) throws IOException {
        List<AdminQueryRepository.ApprovalRecordRow> rows = queryRepository
                .searchApprovalRecords(from, to, action, operator, keyword, 0, 5000);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"approval-ledger-" + LocalDate.now() + ".csv\"");
        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("审批时间,预约单号,访客,审批节点,审批人,审批来源,审批结果,意见,批准门点\r\n");
        for (AdminQueryRepository.ApprovalRecordRow row : rows) {
            sb.append(csv(DATETIME.format(row.createdAt()))).append(',')
                    .append(csv(row.appointmentNo())).append(',')
                    .append(csv(row.visitorName())).append(',')
                    .append(csv(nodeName(row))).append(',')
                    .append(csv(row.operatorName())).append(',')
                    .append(csv(operatorTypeText(row.operatorType()))).append(',')
                    .append(csv(actionText(row.action()))).append(',')
                    .append(csv(row.comment())).append(',')
                    .append(csv(gateNames(row.gateSnapshot()))).append("\r\n");
        }
        response.getWriter().write(sb.toString());
        response.getWriter().flush();
    }

    private Map<String, Object> toView(AdminQueryRepository.ApprovalRecordRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("createdAt", DATETIME.format(row.createdAt()));
        view.put("appointmentNo", row.appointmentNo());
        view.put("visitorName", row.visitorName() == null ? "" : row.visitorName());
        view.put("nodeName", nodeName(row));
        view.put("operatorName", row.operatorName() == null ? "" : row.operatorName());
        view.put("operatorType", row.operatorType());
        view.put("operatorTypeText", operatorTypeText(row.operatorType()));
        view.put("action", row.action());
        view.put("actionText", actionText(row.action()));
        view.put("comment", row.comment() == null ? "" : row.comment());
        view.put("gates", gateNames(row.gateSnapshot()));
        return view;
    }

    private String nodeName(AdminQueryRepository.ApprovalRecordRow row) {
        return row.nodeName() == null || row.nodeName().isBlank() ? "-" : row.nodeName();
    }

    private String operatorTypeText(int type) {
        return switch (type) {
            case 1 -> "员工";
            case 2 -> "管理端";
            case 4 -> "微信小程序";
            default -> "系统";
        };
    }

    private String actionText(int action) {
        return switch (action) {
            case 1 -> "通过";
            case 2 -> "驳回";
            case 3 -> "转交";
            case 4 -> "加签";
            case 5 -> "催办";
            case 6 -> "跳过";
            case 7 -> "抄送";
            default -> "未知(" + action + ")";
        };
    }

    private String gateNames(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return "";
        }
        return String.join("/", Arrays.stream(snapshot.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(id -> {
                    try {
                        return gateService.nameOf(Long.parseLong(id));
                    } catch (NumberFormatException e) {
                        return id;
                    }
                })
                .toList());
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
