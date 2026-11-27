package com.fangke.visitor.approval.model;

import java.util.List;

/**
 * 审批流程快照节点（C-82）。
 *
 * <p>预约提交时把当时的模板（节点顺序、处理角色、门点限制、节点抄送）整条链存下来，
 * 之后管理员改模板**不影响在途单**：在途单永远按提交时的流程走完。
 */
public record FlowNode(int seq, String nodeName, String assigneeRole,
                       boolean allowEditGates, List<String> ccRoles) {

    /** 逗号分隔存库。 */
    public String ccRolesText() {
        return ccRoles == null || ccRoles.isEmpty() ? null : String.join(",", ccRoles);
    }

    public static List<String> splitRoles(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
