package com.fangke.visitor.approval.service;

import com.fangke.visitor.admin.repository.AdminUserRepository;
import com.fangke.visitor.approval.model.ApprovalCc;
import com.fangke.visitor.approval.model.ApprovalNode;
import com.fangke.visitor.approval.model.ApprovalTemplate;
import com.fangke.visitor.approval.repository.ApprovalTemplateRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 审批模板管理（管理端配置多级审批 + 抄送）。
 *
 * <p>校验口径：
 * <ul>
 *   <li>至少 1 个节点；节点名与角色必填，角色必须是系统里存在的角色码</li>
 *   <li>抄送角色同样要在角色表里，重复的自动去重</li>
 *   <li>节点按提交顺序重排为 1..n，避免前端传乱序/断号</li>
 * </ul>
 */
@Service
public class ApprovalTemplateAdminService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTemplateAdminService.class);

    /** 管理端提交的一个节点（抄送用角色码，比让前端传 nodeId 稳）。 */
    public record NodeSpec(String nodeName, String assigneeRole, String approveMode,
                           Boolean allowEditGates, Boolean allowQr, List<String> ccRoles) {
    }

    /** 整份模板：节点 + 提交时抄送。 */
    public record TemplateSpec(String templateName, Boolean enabled, String remark,
                               List<NodeSpec> nodes, List<String> submitCcRoles) {
    }

    private final ApprovalTemplateRepository templateRepository;
    private final AdminUserRepository adminUserRepository;

    public ApprovalTemplateAdminService(ApprovalTemplateRepository templateRepository,
                                        AdminUserRepository adminUserRepository) {
        this.templateRepository = templateRepository;
        this.adminUserRepository = adminUserRepository;
    }

    public List<ApprovalTemplate> list() {
        return templateRepository.list();
    }

    public ApprovalTemplate get(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "审批模板不存在：" + id));
    }

    /** 角色下拉（管理端配置页用）。 */
    public List<AdminUserRepository.RoleRow> roles() {
        return adminUserRepository.roles();
    }

    @Transactional
    public ApprovalTemplate create(TemplateSpec spec) {
        List<NodeSpec> nodes = validate(spec);
        long id = templateRepository.create(text(spec.templateName(), "默认审批流程"),
                ApprovalTemplate.SCOPE_GLOBAL, spec.enabled() == null || spec.enabled(),
                trim(spec.remark()));
        saveChildren(id, nodes, spec.submitCcRoles());
        log.info("新建审批模板 id={} 节点数={}", id, nodes.size());
        return get(id);
    }

    @Transactional
    public ApprovalTemplate update(Long id, TemplateSpec spec) {
        get(id);
        List<NodeSpec> nodes = validate(spec);
        templateRepository.updateMeta(id, text(spec.templateName(), "默认审批流程"),
                spec.enabled() == null || spec.enabled(), trim(spec.remark()));
        saveChildren(id, nodes, spec.submitCcRoles());
        log.info("更新审批模板 id={} 节点数={}", id, nodes.size());
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        templateRepository.delete(id);
        log.info("删除审批模板 id={}", id);
    }

    /** 启用/停用：停用后新预约走兜底单级审批（不会卡死）。 */
    @Transactional
    public ApprovalTemplate setEnabled(Long id, boolean enabled) {
        ApprovalTemplate template = get(id);
        templateRepository.updateMeta(id, template.templateName(), enabled, template.remark());
        return get(id);
    }

    private void saveChildren(Long templateId, List<NodeSpec> nodes, List<String> submitCcRoles) {
        List<ApprovalNode> entities = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeSpec spec = nodes.get(i);
            entities.add(ApprovalNode.of(templateId, i + 1, spec.nodeName().trim(),
                    spec.assigneeRole().trim(), spec.approveMode(),
                    spec.allowEditGates() == null || spec.allowEditGates(),
                    spec.allowQr() == null || spec.allowQr()));
        }
        templateRepository.replaceNodes(templateId, entities);

        List<ApprovalCc> ccs = new ArrayList<>();
        for (String role : distinct(submitCcRoles)) {
            ccs.add(ApprovalCc.toRole(templateId, null, role));
        }
        for (int i = 0; i < nodes.size(); i++) {
            Long nodeId = templateRepository.nodeIdOf(templateId, i + 1);
            for (String role : distinct(nodes.get(i).ccRoles())) {
                ccs.add(ApprovalCc.toRole(templateId, nodeId, role));
            }
        }
        templateRepository.replaceCcs(templateId, ccs);
    }

    private List<NodeSpec> validate(TemplateSpec spec) {
        if (spec == null || spec.nodes() == null || spec.nodes().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "至少要配置一个审批节点");
        }
        Set<String> roleCodes = new LinkedHashSet<>();
        for (AdminUserRepository.RoleRow role : adminUserRepository.roles()) {
            roleCodes.add(role.roleCode());
        }
        for (NodeSpec node : spec.nodes()) {
            if (node.nodeName() == null || node.nodeName().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_MISSING, "节点名称不能为空");
            }
            if (node.assigneeRole() == null || node.assigneeRole().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_MISSING,
                        "节点『" + node.nodeName() + "』必须选择处理角色");
            }
            if (!roleCodes.contains(node.assigneeRole().trim())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "角色不存在：" + node.assigneeRole());
            }
            for (String cc : distinct(node.ccRoles())) {
                if (!roleCodes.contains(cc)) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "抄送角色不存在：" + cc);
                }
            }
        }
        for (String cc : distinct(spec.submitCcRoles())) {
            if (!roleCodes.contains(cc)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "抄送角色不存在：" + cc);
            }
        }
        if (spec.nodes().size() > 10) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "审批节点最多 10 个");
        }
        return spec.nodes();
    }

    private List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                set.add(value.trim());
            }
        }
        return new ArrayList<>(set);
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
