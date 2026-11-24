package com.fangke.visitor.approval.repository;

import com.fangke.visitor.approval.model.ApprovalCc;
import com.fangke.visitor.approval.model.ApprovalNode;
import com.fangke.visitor.approval.model.ApprovalTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 审批模板仓储（V24）：模板 + 节点 + 抄送。
 *
 * <p>节点按 {@code seq} **原地更新**（保留 id），只删掉本次没提交的尾部节点；
 * 这样历史任务的 {@code node_id} 不会因为改配置而全部失效。
 */
public interface ApprovalTemplateRepository {

    List<ApprovalTemplate> list();

    Optional<ApprovalTemplate> findById(Long id);

    /** 当前生效的全局模板（多套时取 id 最小的一套，避免歧义）。 */
    Optional<ApprovalTemplate> findEnabledGlobal();

    Optional<ApprovalNode> findNode(Long nodeId);

    /** 新建模板，返回主键。 */
    long create(String templateName, String scope, boolean enabled, String remark);

    void updateMeta(Long id, String templateName, boolean enabled, String remark);

    /** 按 seq 原地更新节点，删除本次未提交的节点。 */
    void replaceNodes(Long templateId, List<ApprovalNode> nodes);

    Long nodeIdOf(Long templateId, int seq);

    /** 抄送配置整体替换（量很小，直接删了重插）。 */
    void replaceCcs(Long templateId, List<ApprovalCc> ccs);

    void delete(Long id);
}
