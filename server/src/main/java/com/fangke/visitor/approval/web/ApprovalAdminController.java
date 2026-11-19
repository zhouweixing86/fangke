package com.fangke.visitor.approval.web;

import com.fangke.visitor.approval.model.ApprovalConfig;
import com.fangke.visitor.approval.service.ApprovalService;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审批配置（docs/11 §5.4）。
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

    private final ApprovalService approvalService;

    public ApprovalAdminController(ApprovalService approvalService) {
        this.approvalService = approvalService;
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
}
