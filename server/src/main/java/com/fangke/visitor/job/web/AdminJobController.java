package com.fangke.visitor.job.web;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.job.repository.JobRunLog;
import com.fangke.visitor.job.repository.JobRunLogRepository;
import com.fangke.visitor.job.service.JobCodes;
import com.fangke.visitor.job.service.JobScheduler;
import com.fangke.visitor.security.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务运维（docs/11 §5.9）：查看每个任务最近执行结果与处理条数。
 */
@RestController
@RequestMapping("/api/admin/job")
public class AdminJobController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JobRunLogRepository repository;
    private final JobScheduler scheduler;

    public AdminJobController(JobRunLogRepository repository, JobScheduler scheduler) {
        this.repository = repository;
        this.scheduler = scheduler;
    }

    @GetMapping("/log")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String jobCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = repository.list(jobCode, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        return ApiResponse.ok(PageResult.of(repository.count(jobCode), safePage, safeSize, list));
    }

    /** 任务字典：前端筛选下拉用。 */
    @GetMapping("/codes")
    public ApiResponse<List<Map<String, String>>> codes(HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        return ApiResponse.ok(List.of(
                code(JobCodes.APPROVAL_REMIND, "审批超时催办"),
                code(JobCodes.APPOINTMENT_EXPIRE, "预约过期"),
                code(JobCodes.VISIT_AUTO_CHECKOUT, "在访超时签离"),
                code(JobCodes.NOTIFICATION_RETRY, "短信重试"),
                code(JobCodes.DATA_RETENTION, "保留期清理"),
                code(JobCodes.RECONCILE, "每日名单对账"),
                code(JobCodes.GRANT_CLEANUP, "过期授权清理"),
                code(JobCodes.QR_CLEANUP, "二维码票据清理")));
    }

    /** 手动执行一次（运维兜底/验收用），返回处理条数。 */
    @PostMapping("/{jobCode}/run")
    @AuditLog(module = "JOB", action = "RUN")
    public ApiResponse<Map<String, Object>> run(@PathVariable String jobCode,
                                                HttpServletRequest request) {
        RoleGuard.requireAdmin(request);
        int affected = scheduler.runOnce(jobCode);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobCode", jobCode);
        view.put("affected", affected);
        return ApiResponse.ok(view);
    }

    private Map<String, String> code(String code, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        return item;
    }

    private Map<String, Object> toView(JobRunLog row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("jobCode", row.jobCode());
        view.put("startedAt", row.startedAt() == null ? "" : DATETIME.format(row.startedAt()));
        view.put("finishedAt", row.finishedAt() == null ? "" : DATETIME.format(row.finishedAt()));
        view.put("status", row.status());
        view.put("statusText", JobRunLog.statusText(row.status()));
        view.put("affected", row.affected());
        view.put("message", row.message() == null ? "" : row.message());
        return view;
    }
}
