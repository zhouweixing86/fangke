package com.fangke.visitor.visit.web;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.security.RoleGuard;
import com.fangke.visitor.visit.model.VisitDict;
import com.fangke.visitor.visit.model.VisitRegister;
import com.fangke.visitor.visit.service.VisitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 来访登记 + 基础字典（V26，对齐参考站「来访信息 / 事由信息 / 区域信息 / 岗位」）。
 */
@RestController
@RequestMapping("/api/admin/visit")
public class VisitController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record DictRequest(String dictType, String name, Integer sort, Integer status, String remark) {
    }

    private final VisitService service;

    public VisitController(VisitService service) {
        this.service = service;
    }

    /* ---------------- 基础字典 ---------------- */

    @GetMapping("/dict")
    public ApiResponse<List<VisitDict>> dict(@RequestParam String type,
                                             @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return ApiResponse.ok(service.dict(type, enabledOnly));
    }

    @PostMapping("/dict")
    @AuditLog(module = "VISIT", action = "CREATE_DICT")
    public ApiResponse<Map<String, Object>> createDict(@RequestBody DictRequest request) {
        long id = service.createDict(request.dictType(), request.name(), request.sort(), request.remark());
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/dict/{id}")
    @AuditLog(module = "VISIT", action = "UPDATE_DICT")
    public ApiResponse<Void> updateDict(@PathVariable Long id, @RequestBody DictRequest request) {
        service.updateDict(id, request.name(), request.sort(), request.status(), request.remark());
        return ApiResponse.ok();
    }

    @DeleteMapping("/dict/{id}")
    @AuditLog(module = "VISIT", action = "DELETE_DICT")
    public ApiResponse<Void> deleteDict(@PathVariable Long id) {
        service.deleteDict(id);
        return ApiResponse.ok();
    }

    /* ---------------- 来访登记 ---------------- */

    @GetMapping("/register")
    public ApiResponse<PageResult<VisitRegister>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Long causeId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.search(keyword, areaId, causeId, status, from, to, page, size));
    }

    @PostMapping("/register")
    @AuditLog(module = "VISIT", action = "REGISTER")
    public ApiResponse<Map<String, Object>> register(@RequestBody VisitService.RegisterRequest request,
                                                     HttpServletRequest http) {
        long id = service.register(request, RoleGuard.currentUserId(http));
        return ApiResponse.ok(Map.of("id", id));
    }

    @PostMapping("/register/{id}/leave")
    @AuditLog(module = "VISIT", action = "LEAVE")
    public ApiResponse<VisitRegister> leave(@PathVariable Long id) {
        return ApiResponse.ok(service.leave(id));
    }

    /** 导出 CSV（UTF-8 BOM，Excel 直接打开）。 */
    @GetMapping("/register/export")
    public void export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Long causeId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) throws IOException {
        List<VisitRegister> rows = service.search(keyword, areaId, causeId, status, from, to, 1, 5000).getList();
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"visit-register-" + LocalDate.now() + ".csv\"");
        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("区域,事由,姓名,手机号,车牌号,来访单位,人数,到达时间,是否离开,离开时间,备注\r\n");
        for (VisitRegister r : rows) {
            sb.append(csv(r.areaName())).append(',')
                    .append(csv(r.causeName())).append(',')
                    .append(csv(r.visitorName())).append(',')
                    .append(csv(r.phoneMask())).append(',')
                    .append(csv(r.plateNo())).append(',')
                    .append(csv(r.company())).append(',')
                    .append(r.personCount()).append(',')
                    .append(csv(DATETIME.format(r.arrivalTime()))).append(',')
                    .append(r.inside() ? "否" : "是").append(',')
                    .append(csv(r.leaveTime() == null ? "" : DATETIME.format(r.leaveTime()))).append(',')
                    .append(csv(r.remark())).append("\r\n");
        }
        response.getWriter().write(sb.toString());
        response.getWriter().flush();
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

    /** 前端下拉用：一次取三类启用的字典。 */
    @GetMapping("/dict/options")
    public ApiResponse<Map<String, List<VisitDict>>> options() {
        Map<String, List<VisitDict>> view = new LinkedHashMap<>();
        view.put(VisitDict.TYPE_CAUSE, service.dict(VisitDict.TYPE_CAUSE, true));
        view.put(VisitDict.TYPE_AREA, service.dict(VisitDict.TYPE_AREA, true));
        view.put(VisitDict.TYPE_POST, service.dict(VisitDict.TYPE_POST, true));
        return ApiResponse.ok(view);
    }
}
