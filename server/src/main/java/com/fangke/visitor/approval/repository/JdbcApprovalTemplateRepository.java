package com.fangke.visitor.approval.repository;

import com.fangke.visitor.approval.model.ApprovalCc;
import com.fangke.visitor.approval.model.ApprovalNode;
import com.fangke.visitor.approval.model.ApprovalTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcApprovalTemplateRepository implements ApprovalTemplateRepository {

    private static final RowMapper<ApprovalTemplate> TEMPLATE_MAPPER = (rs, i) -> new ApprovalTemplate(
            rs.getLong("id"),
            rs.getString("template_name"),
            rs.getString("scope"),
            rs.getInt("enabled") == 1,
            rs.getString("remark"),
            List.of(),
            List.of());

    private static final RowMapper<ApprovalNode> NODE_MAPPER = (rs, i) -> new ApprovalNode(
            rs.getLong("id"),
            rs.getLong("template_id"),
            rs.getInt("seq"),
            rs.getString("node_name"),
            rs.getInt("assignee_type"),
            rs.getString("assignee_role"),
            rs.getString("approve_mode"),
            rs.getInt("allow_edit_gates") == 1);

    private static final RowMapper<ApprovalCc> CC_MAPPER = (rs, i) -> {
        long rawNodeId = rs.getLong("node_id");
        Long nodeId = rs.wasNull() ? null : rawNodeId;
        return new ApprovalCc(
                rs.getLong("id"),
                rs.getLong("template_id"),
                nodeId,
                rs.getInt("target_type"),
                rs.getString("target_value"),
                rs.getInt("channel"),
                rs.getInt("enabled") == 1);
    };

    private final JdbcTemplate jdbc;

    public JdbcApprovalTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ApprovalTemplate> list() {
        return jdbc.query("SELECT * FROM approval_template ORDER BY id", TEMPLATE_MAPPER).stream()
                .map(t -> loadDetail(t, true))
                .toList();
    }

    @Override
    public Optional<ApprovalTemplate> findById(Long id) {
        return jdbc.query("SELECT * FROM approval_template WHERE id = ?", TEMPLATE_MAPPER, id).stream()
                .findFirst()
                .map(t -> loadDetail(t, true));
    }

    @Override
    public Optional<ApprovalTemplate> findEnabledGlobal() {
        return jdbc.query(
                        "SELECT * FROM approval_template WHERE scope = 'GLOBAL' AND enabled = 1 ORDER BY id LIMIT 1",
                        TEMPLATE_MAPPER)
                .stream().findFirst()
                .map(t -> loadDetail(t, true));
    }

    @Override
    public Optional<ApprovalNode> findNode(Long nodeId) {
        if (nodeId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM approval_node WHERE id = ?", NODE_MAPPER, nodeId)
                .stream().findFirst();
    }

    @Override
    public long create(String templateName, String scope, boolean enabled, String remark) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO approval_template (template_name, scope, enabled, remark) VALUES (?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, templateName);
            ps.setString(2, scope == null || scope.isBlank() ? ApprovalTemplate.SCOPE_GLOBAL : scope);
            ps.setInt(3, enabled ? 1 : 0);
            ps.setString(4, remark);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("审批模板写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateMeta(Long id, String templateName, boolean enabled, String remark) {
        jdbc.update("UPDATE approval_template SET template_name = ?, enabled = ?, remark = ? WHERE id = ?",
                templateName, enabled ? 1 : 0, remark, id);
    }

    @Override
    @Transactional
    public void replaceNodes(Long templateId, List<ApprovalNode> nodes) {
        List<ApprovalNode> ordered = nodes == null ? List.of()
                : nodes.stream().sorted((a, b) -> Integer.compare(a.seq(), b.seq())).toList();
        List<Integer> keep = new ArrayList<>();
        int seq = 1;
        for (ApprovalNode node : ordered) {
            // 客户端传的 seq 只用于排序，实际按 1..n 重排，避免中间断号
            jdbc.update("""
                            INSERT INTO approval_node (template_id, seq, node_name, assignee_type,
                                assignee_role, approve_mode, allow_edit_gates)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON DUPLICATE KEY UPDATE node_name = VALUES(node_name),
                                assignee_type = VALUES(assignee_type), assignee_role = VALUES(assignee_role),
                                approve_mode = VALUES(approve_mode),
                                allow_edit_gates = VALUES(allow_edit_gates)
                            """,
                    templateId, seq, node.nodeName(), ApprovalNode.TYPE_ROLE, node.assigneeRole(),
                    ApprovalNode.normalizeMode(node.approveMode()),
                    node.allowEditGates() ? 1 : 0);
            keep.add(seq);
            seq++;
        }
        if (keep.isEmpty()) {
            jdbc.update("DELETE FROM approval_node WHERE template_id = ?", templateId);
        } else {
            String placeholders = String.join(",", java.util.Collections.nCopies(keep.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(templateId);
            args.addAll(keep);
            jdbc.update("DELETE FROM approval_node WHERE template_id = ? AND seq NOT IN ("
                    + placeholders + ")", args.toArray());
        }
    }

    @Override
    public Long nodeIdOf(Long templateId, int seq) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM approval_node WHERE template_id = ? AND seq = ?", Long.class, templateId, seq);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    @Transactional
    public void replaceCcs(Long templateId, List<ApprovalCc> ccs) {
        jdbc.update("DELETE FROM approval_cc WHERE template_id = ?", templateId);
        if (ccs == null) {
            return;
        }
        for (ApprovalCc cc : ccs) {
            jdbc.update("""
                            INSERT INTO approval_cc (template_id, node_id, target_type, target_value,
                                channel, enabled) VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    templateId, cc.nodeId(), cc.targetType(), cc.targetValue(),
                    cc.channel(), cc.enabled() ? 1 : 0);
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        jdbc.update("DELETE FROM approval_cc WHERE template_id = ?", id);
        jdbc.update("DELETE FROM approval_node WHERE template_id = ?", id);
        jdbc.update("DELETE FROM approval_template WHERE id = ?", id);
    }

    private ApprovalTemplate loadDetail(ApprovalTemplate base, boolean withChildren) {
        if (!withChildren) {
            return base;
        }
        List<ApprovalNode> nodes = jdbc.query(
                "SELECT * FROM approval_node WHERE template_id = ? ORDER BY seq", NODE_MAPPER, base.id());
        List<ApprovalCc> ccs = jdbc.query(
                "SELECT * FROM approval_cc WHERE template_id = ? ORDER BY id", CC_MAPPER, base.id());
        return new ApprovalTemplate(base.id(), base.templateName(), base.scope(), base.enabled(),
                base.remark(), nodes, ccs);
    }
}
