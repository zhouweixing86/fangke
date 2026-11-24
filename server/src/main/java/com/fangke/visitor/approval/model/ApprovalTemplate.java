package com.fangke.visitor.approval.model;

import java.util.List;

/**
 * 审批模板（V24）：一套节点 + 抄送配置。
 *
 * <p>当前只有一套 {@code scope=GLOBAL} 的启用模板；表结构里留了 scope 与 enabled，
 * 以后要按门点/访客类型分流程时不用改表。
 */
public record ApprovalTemplate(Long id, String templateName, String scope, boolean enabled,
                               String remark, List<ApprovalNode> nodes, List<ApprovalCc> ccs) {

    public static final String SCOPE_GLOBAL = "GLOBAL";

    /** 按 seq 排好序的节点（仓储已排序，这里再兜一层，避免配置顺序错乱）。 */
    public List<ApprovalNode> orderedNodes() {
        return nodes == null ? List.of()
                : nodes.stream().sorted((a, b) -> Integer.compare(a.seq(), b.seq())).toList();
    }

    public ApprovalNode firstNode() {
        return orderedNodes().stream().findFirst().orElse(null);
    }

    public ApprovalNode nodeAfter(int seq) {
        return orderedNodes().stream().filter(n -> n.seq() > seq).findFirst().orElse(null);
    }

    /** 某节点的抄送配置。 */
    public List<ApprovalCc> ccsOfNode(Long nodeId) {
        if (ccs == null || nodeId == null) {
            return List.of();
        }
        return ccs.stream().filter(c -> c.enabled() && nodeId.equals(c.nodeId())).toList();
    }

    /** 提交时的抄送配置。 */
    public List<ApprovalCc> submitCcs() {
        if (ccs == null) {
            return List.of();
        }
        return ccs.stream().filter(c -> c.enabled() && c.onSubmit()).toList();
    }
}
