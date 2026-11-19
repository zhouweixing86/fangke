package com.fangke.visitor.approval.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.approval.model.ApprovalConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcApprovalConfigRepository implements ApprovalConfigRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcApprovalConfigRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public ApprovalConfig loadGlobal() {
        List<ApprovalConfig> list = jdbc.query("""
                        SELECT id, scope, approval_level, default_l1_user, l2_candidate_ids, l2_role,
                               reminder_interval_min, reminder_max_per_task
                        FROM approval_config WHERE scope = 'GLOBAL' LIMIT 1
                        """,
                (rs, i) -> {
                    long defaultL1 = rs.getLong("default_l1_user");
                    Long defaultL1User = rs.wasNull() ? null : defaultL1;
                    return new ApprovalConfig(
                            rs.getLong("id"),
                            rs.getString("scope"),
                            rs.getInt("approval_level"),
                            defaultL1User,
                            parseIds(rs.getString("l2_candidate_ids")),
                            rs.getString("l2_role"),
                            rs.getInt("reminder_interval_min"),
                            rs.getInt("reminder_max_per_task"));
                });
        return list.stream().findFirst().orElse(ApprovalConfig.defaults());
    }

    @Override
    public void saveGlobal(ApprovalConfig config) {
        String candidateJson = toJson(config.l2CandidateIds());
        Integer exists = jdbc.queryForObject("SELECT COUNT(1) FROM approval_config WHERE scope = 'GLOBAL'",
                Integer.class);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO approval_config (scope, approval_level, default_l1_user, l2_candidate_ids, "
                            + "l2_role, reminder_interval_min, reminder_max_per_task) "
                            + "VALUES ('GLOBAL', ?, ?, ?, ?, ?, ?)",
                    config.approvalLevel(), config.defaultL1User(), candidateJson, config.roleOrDefault(),
                    config.reminderIntervalMin(), config.reminderMaxPerTask());
        } else {
            jdbc.update("""
                            UPDATE approval_config SET approval_level = ?, default_l1_user = ?, l2_candidate_ids = ?,
                                l2_role = ?, reminder_interval_min = ?, reminder_max_per_task = ?
                            WHERE scope = 'GLOBAL'
                            """,
                    config.approvalLevel(), config.defaultL1User(), candidateJson, config.roleOrDefault(),
                    config.reminderIntervalMin(), config.reminderMaxPerTask());
        }
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        try {
            return mapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }
}
