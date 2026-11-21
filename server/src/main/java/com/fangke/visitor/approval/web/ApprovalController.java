package com.fangke.visitor.approval.web;

import com.fangke.visitor.approval.service.ApprovalService;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.security.AppAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 员工审批接口（docs/11 §3）。
 *
 * <p><b>身份来源</b>：小程序登录态在 P3 接入；当前开发阶段用请求头
 * {@code X-Employee-Id} 模拟当前登录员工，上线前必须替换为 token 解析。
 */
@RestController
@RequestMapping("/api/app")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final AppProperties props;

    public ApprovalController(ApprovalService approvalService, AppProperties props) {
        this.approvalService = approvalService;
        this.props = props;
    }

    @GetMapping("/todo/count")
    public ApiResponse<Map<String, Object>> todoCount(HttpServletRequest request) {
        long employeeId = currentEmployee(request);
        return ApiResponse.ok(Map.of("pending", approvalService.todoCount(employeeId)));
    }

    @GetMapping("/approval/todo")
    public ApiResponse<PageResult<ApprovalService.TodoView>> todo(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(approvalService.todoList(currentEmployee(request), page, size));
    }

    @GetMapping("/approval/{taskId}")
    public ApiResponse<ApprovalService.DetailView> detail(@PathVariable Long taskId,
                                                          HttpServletRequest request) {
        return ApiResponse.ok(approvalService.detail(taskId, currentEmployee(request)));
    }

    @PostMapping("/approval/{taskId}/pass")
    public ApiResponse<ApprovalService.PassResult> pass(@PathVariable Long taskId,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        String comment = body == null ? null : asString(body.get("comment"));
        Long nextApproverId = body == null ? null : asLong(body.get("nextApproverId"));
        List<Long> approvedGateIds = body == null ? null : asLongList(body.get("approvedGateIds"));
        Boolean qrApproved = body == null ? null : asBoolean(body.get("qrApproved"));
        return ApiResponse.ok(approvalService.pass(taskId, currentEmployee(request), comment,
                nextApproverId, approvedGateIds, qrApproved));
    }

    @PostMapping("/approval/{taskId}/reject")
    public ApiResponse<Void> reject(@PathVariable Long taskId,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        approvalService.reject(taskId, currentEmployee(request),
                body == null ? null : asString(body.get("comment")));
        return ApiResponse.ok();
    }

    @PostMapping("/approval/{taskId}/transfer")
    public ApiResponse<Void> transfer(@PathVariable Long taskId,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        approvalService.transfer(taskId, currentEmployee(request),
                asLong(body.get("targetUserId")), asString(body.get("comment")));
        return ApiResponse.ok();
    }

    @PostMapping("/approval/{taskId}/countersign")
    public ApiResponse<Void> countersign(@PathVariable Long taskId,
                                         @RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        approvalService.countersign(taskId, currentEmployee(request), asLong(body.get("targetUserId")));
        return ApiResponse.ok();
    }

    @PostMapping("/approval/{taskId}/remind")
    public ApiResponse<Map<String, Object>> remind(@PathVariable Long taskId, HttpServletRequest request) {
        int remaining = approvalService.remind(taskId, currentEmployee(request));
        return ApiResponse.ok(Map.of("remaining", remaining));
    }

    private long currentEmployee(HttpServletRequest request) {
        // 优先用 App token 里的员工身份（M10 后的正常路径）
        if (AppAuthInterceptor.isEmployee(request)) {
            Long id = AppAuthInterceptor.subjectId(request);
            if (id != null) {
                return id;
            }
        }
        // 仅当显式开启时才允许用请求头模拟身份（默认关闭，避免生产环境越权）
        if (!props.getApp().isAllowEmployeeHeader()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先以员工身份登录");
        }
        String header = request.getHeader("X-Employee-Id");
        if (header == null || header.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先以员工身份登录");
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "X-Employee-Id 必须是数字");
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "期望数字：" + value);
        }
    }

    /** 「同意二维码开门」标志：{@code null} 表示不改。 */
    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "qrApproved 必须是布尔值");
    }

    /**
     * 审批人调整后的门点集合。{@code null} 表示不改（继承访客申请）；
     * 空数组会由服务层拒绝（至少要保留一个门点）。
     */
    private List<Long> asLongList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "approvedGateIds 必须是数组");
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            Long id = asLong(item);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }
}
