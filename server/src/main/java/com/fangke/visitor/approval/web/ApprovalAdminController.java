package com.fangke.visitor.approval.web;

import com.fangke.visitor.approval.model.ApprovalCc;
import com.fangke.visitor.approval.model.ApprovalConfig;
import com.fangke.visitor.approval.model.ApprovalNode;
import com.fangke.visitor.approval.model.ApprovalTemplate;
import com.fangke.visitor.approval.service.ApprovalService;
import com.fangke.visitor.approval.service.ApprovalTemplateAdminService;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批配置（docs/11 §5.4）：
 * <ul>
 *   <li>{@code /config}：催办间隔/上限等全局参数</li>
 *   <li>{@code /template}：多级审批模板（节点 + 抄送），管理端可视化配置</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/approval")
public class ApprovalAdminController {

    public record ConfigRequest(
            Integer approvalLevel,
            Long defaultL1User,
            List<Long> l2CandidateIds,
            String l2Role,
            Integer reminderIntervalMin,
            Integer reminderMaxPerTask) {
    }

    public record NodeRequest(String nodeName, String assigneeRole, String approveMode,
                              Boolean allowEditGates, Boolean allowQr, List<String> ccRoles) {
    }

    public record TemplateRequest(String templateName, Boolean enabled, String remark,
                                  List<NodeRequest> nodes, List<String> submitCcRoles) {
    }

    private final ApprovalService approvalService;
    private final ApprovalTemplateAdminService templateService;

    public ApprovalAdminController(ApprovalService approvalService,
                                   ApprovalTemplateAdminService templateService) {
        this.approvalService = approvalService;
        this.templateService = templateService;
    }

    @GetMapping("/config")
    public ApiResponse<ApprovalConfig> get() {
        return ApiResponse.ok(approvalService.getConfig());
    }

    @PutMapping("/config")
    @AuditLog(module = "APPROVAL", action = "UPDATE_CONFIG")
    public ApiResponse<ApprovalConfig> update(@RequestBody ConfigRequest request) {
        ApprovalConfig current = approvalService.getConfig();
        ApprovalConfig config = new ApprovalConfig(
                current.id(),
                "GLOBAL",
                request.approvalLevel() == null ? current.approvalLevel() : request.approvalLevel(),
                request.defaultL1User() == null ? current.defaultL1User() : request.defaultL1User(),
                request.l2CandidateIds() == null ? current.l2CandidateIds() : request.l2CandidateIds(),
                request.l2Role() == null ? current.roleOrDefault() : request.l2Role(),
                request.reminderIntervalMin() == null ? current.reminderIntervalMin() : request.reminderIntervalMin(),
                request.reminderMaxPerTask() == null ? current.reminderMaxPerTask() : request.reminderMaxPerTask());
        return ApiResponse.ok(approvalService.updateConfig(config));
    }

    /* ---------------- 审批模板（多级 + 抄送） ---------------- */

    /** 角色下拉：配置节点处理人 / 抄送对象用。 */
    @GetMapping("/template/roles")
    public ApiResponse<List<Map<String, Object>>> roles() {
        List<Map<String, Object>> list = templateService.roles().stream().map(r -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("roleCode", r.roleCode());
            view.put("roleName", r.roleName());
            return view;
        }).toList();
        return ApiResponse.ok(list);
    }

    @GetMapping("/template")
    public ApiResponse<List<Map<String, Object>>> templates() {
        return ApiResponse.ok(templateService.list().stream().map(this::toView).toList());
    }

    @GetMapping("/template/{id}")
    public ApiResponse<Map<String, Object>> template(@PathVariable Long id) {
        return ApiResponse.ok(toView(templateService.get(id)));
    }

    @PostMapping("/template")
    @AuditLog(module = "APPROVAL", action = "CREATE_TEMPLATE")
    public ApiResponse<Map<String, Object>> create(@RequestBody TemplateRequest request) {
        return ApiResponse.ok(toView(templateService.create(toSpec(request))));
    }

    @PutMapping("/template/{id}")
    @AuditLog(module = "APPROVAL", action = "UPDATE_TEMPLATE")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody TemplateRequest request) {
        return ApiResponse.ok(toView(templateService.update(id, toSpec(request))));
    }

    @PutMapping("/template/{id}/enabled")
    @AuditLog(module = "APPROVAL", action = "TOGGLE_TEMPLATE")
    public ApiResponse<Map<String, Object>> toggle(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body) {
        boolean enabled = body != null && !Boolean.FALSE.equals(body.get("enabled"))
                && !"false".equalsIgnoreCase(String.valueOf(body.get("enabled")));
        return ApiResponse.ok(toView(templateService.setEnabled(id, enabled)));
    }

    @DeleteMapping("/template/{id}")
    @AuditLog(module = "APPROVAL", action = "DELETE_TEMPLATE")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ApiResponse.ok();
    }

    /* ---------------- 转换 ---------------- */

    private ApprovalTemplateAdminService.TemplateSpec toSpec(TemplateRequest request) {
        List<ApprovalTemplateAdminService.NodeSpec> nodes = new ArrayList<>();
        if (request != null && request.nodes() != null) {
            for (NodeRequest node : request.nodes()) {
                nodes.add(new ApprovalTemplateAdminService.NodeSpec(node.nodeName(), node.assigneeRole(),
                        node.approveMode(), node.allowEditGates(), node.allowQr(), node.ccRoles()));
            }
        }
        return new ApprovalTemplateAdminService.TemplateSpec(
                request == null ? null : request.templateName(),
                request == null ? null : request.enabled(),
                request == null ? null : request.remark(),
                nodes,
                request == null ? List.of() : request.submitCcRoles());
    }

    private Map<String, Object> toView(ApprovalTemplate template) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", template.id());
        view.put("templateName", template.templateName());
        view.put("scope", template.scope());
        view.put("enabled", template.enabled());
        view.put("remark", template.remark() == null ? "" : template.remark());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ApprovalNode node : template.orderedNodes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.id());
            item.put("seq", node.seq());
            item.put("nodeName", node.nodeName());
            item.put("assigneeRole", node.assigneeRole());
            item.put("approveMode", node.approveMode());
            item.put("approveModeText", node.modeText());
            item.put("allowEditGates", node.allowEditGates());
            item.put("allowQr", node.allowQr());
            item.put("ccRoles", template.ccsOfNode(node.id()).stream()
                    .map(ApprovalCc::targetValue).toList());
            nodes.add(item);
        }
        view.put("nodes", nodes);
        view.put("submitCcRoles", template.submitCcs().stream()
                .map(ApprovalCc::targetValue).toList());
        return view;
    }
}
