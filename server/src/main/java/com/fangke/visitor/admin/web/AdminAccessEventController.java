package com.fangke.visitor.admin.web;

import com.fangke.visitor.admin.repository.AdminQueryRepository;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
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
 * 管理端通行记录查询（docs/11 §5.6）。照片通过
 * {@code /api/admin/access/event/{id}/media} 取预签名 URL。
 */
@RestController
@RequestMapping("/api/admin/access/event")
public class AdminAccessEventController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AdminQueryRepository queryRepository;

    public AdminAccessEventController(AdminQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    /** 导出 CSV（UTF-8 BOM，Excel 直接打开不乱码），上限 1 万条。 */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String keyword,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        java.util.List<AdminQueryRepository.EventRow> rows =
                queryRepository.searchEvents(from, to, result, keyword, 0, 10000);

        response.setContentType("text/csv;charset=UTF-8");
        String fileName = "access-events-" + LocalDate.now() + ".csv";
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("通行时间,设备,方向,识别方式,人员编号,姓名,结果,来源\r\n");
        for (AdminQueryRepository.EventRow row : rows) {
            sb.append(csv(DATETIME.format(row.eventTime()))).append(',')
                    .append(csv(row.deviceSn())).append(',')
                    .append(csv(row.direction() == 1 ? "进" : "出")).append(',')
                    .append(csv(identifyText(row.identifyType()))).append(',')
                    .append(csv(row.personNo())).append(',')
                    .append(csv(row.personName())).append(',')
                    .append(csv(row.resultStatus() == 1 ? "通过" : "失败")).append(',')
                    .append(csv(row.source() == 3 ? "人工开门" : "设备回调")).append("\r\n");
        }
        response.getWriter().write(sb.toString());
        response.getWriter().flush();
    }

    /** CSV 转义：含逗号/引号/换行时用双引号包裹并把内部引号翻倍。 */
    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = queryRepository
                .searchEvents(from, to, result, keyword, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        long total = queryRepository.countEvents(from, to, result, keyword);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, list));
    }

    private Map<String, Object> toView(AdminQueryRepository.EventRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("eventTime", DATETIME.format(row.eventTime()));
        view.put("deviceSn", row.deviceSn());
        view.put("direction", row.direction());
        view.put("directionText", row.direction() == 1 ? "进" : "出");
        view.put("identifyType", row.identifyType());
        view.put("identifyTypeText", identifyText(row.identifyType()));
        view.put("personNo", row.personNo() == null ? "" : row.personNo());
        view.put("personName", row.personName() == null ? "" : row.personName());
        view.put("resultStatus", row.resultStatus());
        view.put("resultText", row.resultStatus() == 1 ? "通过" : "失败");
        view.put("source", row.source());
        view.put("sourceText", row.source() == 3 ? "人工开门" : "设备回调");
        view.put("hasMedia", row.mediaCount() > 0);
        return view;
    }

    private String identifyText(int type) {
        return switch (type) {
            case 0 -> "人脸";
            case 2 -> "人证";
            case 3 -> "IC卡";
            case 5 -> "二维码";
            case 9 -> "人工开门";
            default -> "其他";
        };
    }
}
