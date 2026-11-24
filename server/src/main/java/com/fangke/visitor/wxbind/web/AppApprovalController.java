package com.fangke.visitor.wxbind.web;

import com.fangke.visitor.approval.service.ApprovalService;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.security.AppAuthInterceptor;
import com.fangke.visitor.wxbind.repository.WxBindingRepository;
import com.fangke.visitor.wxbind.service.WxBindService;
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
 * 小程序「我的审批」（C-76）。
 *
 * <p>身份来自扫码绑定的后台角色：审批模板节点配了哪个角色，绑了该角色的微信用户就能处理；
 * 通过后照常流转到下一节点（如「领导」复核），最后一节点通过才下发门禁授权。
 *
 * <p>注意：**会签节点**的任务是派给具体后台账号的，小程序只能看/办角色任务。
 */
@RestController
@RequestMapping("/api/app/approval")
public class AppApprovalController {

    private final ApprovalService approvalService;
    private final WxBindService wxBindService;
    private final WxBindingRepository bindingRepository;

    public AppApprovalController(ApprovalService approvalService, WxBindService wxBindService,
                                 WxBindingRepository bindingRepository) {
        this.approvalService = approvalService;
        this.wxBindService = wxBindService;
        this.bindingRepository = bindingRepository;
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> count(HttpServletRequest request) {
        long bindId = AppAuthInterceptor.requireApprover(request);
        return ApiResponse.ok(Map.of("pending", approvalService.appTodoCount(roleCodes(bindId))));
    }

    @GetMapping("/todo")
    public ApiResponse<PageResult<ApprovalService.TodoView>> todo(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long bindId = AppAuthInterceptor.requireApprover(request);
        return ApiResponse.ok(approvalService.appTodoList(roleCodes(bindId), page, size));
    }

    @GetMapping("/done")
    public ApiResponse<PageResult<ApprovalService.DoneView>> done(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long bindId = AppAuthInterceptor.requireApprover(request);
        return ApiResponse.ok(approvalService.appDoneList(bindId, page, size));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<ApprovalService.DetailView> detail(@PathVariable Long taskId,
                                                          HttpServletRequest request) {
        long bindId = AppAuthInterceptor.requireApprover(request);
        return ApiResponse.ok(approvalService.appDetail(taskId, roleCodes(bindId)));
    }

    @PostMapping("/{taskId}/pass")
    public ApiResponse<ApprovalService.PassResult> pass(@PathVariable Long taskId,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        long bindId = AppAuthInterceptor.requireApprover(request);
        String operatorName = bindingRepository.findById(bindId)
                .map(WxBindingRepository.Binding::name).orElse("微信审批人");
        String comment = body == null ? null : asString(body.get("comment"));
        List<Long> gates = body == null ? null : asLongList(body.get("approvedGateIds"));
        Boolean qrApproved = body == null ? null : asBoolean(body.get("qrApproved"));
        return ApiResponse.ok(approvalService.appPass(taskId, bindId, operatorName,
                roleCodes(bindId), comment, gates, qrApproved));
    }

    @PostMapping("/{taskId}/reject")
    public ApiResponse<Void> reject(@PathVariable Long taskId,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        long bindId = AppAuthInterceptor.requireApprover(request);
        String operatorName = bindingRepository.findById(bindId)
                .map(WxBindingRepository.Binding::name).orElse("微信审批人");
        approvalService.appReject(taskId, bindId, operatorName, roleCodes(bindId),
                body == null ? null : asString(body.get("comment")));
        return ApiResponse.ok();
    }

    /** 以数据库为准取角色（后台改了角色，小程序 token 可能还是旧的）。 */
    private List<String> roleCodes(long bindId) {
        return wxBindService.roleCodes(bindId);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

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

    private List<Long> asLongList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "approvedGateIds 必须是数组");
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            if (item instanceof Number n) {
                ids.add(n.longValue());
            } else {
                try {
                    ids.add(Long.parseLong(String.valueOf(item)));
                } catch (NumberFormatException e) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "门点 ID 必须是数字：" + item);
                }
            }
        }
        return ids;
    }
}
