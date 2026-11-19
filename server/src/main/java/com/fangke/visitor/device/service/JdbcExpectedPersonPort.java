package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.PersonPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 期望名单来自 access_grant：门点匹配 + 授权有效 + 状态生效。
 */
@Repository
public class JdbcExpectedPersonPort implements ExpectedPersonPort {

    private static final int GRANT_ACTIVE = 1;
    private static final int SUBJECT_EMPLOYEE = 1;

    private static final String SQL = """
            SELECT person_no, subject_type
            FROM access_grant
            WHERE gate_id = ? AND status = ? AND valid_from <= ? AND valid_to >= ?
            ORDER BY id
            """;

    private final JdbcTemplate jdbc;

    public JdbcExpectedPersonPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExpectedPerson> expectedOn(Long gateId, Instant now) {
        LocalDateTime at = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        Timestamp ts = Timestamp.valueOf(at);
        List<ExpectedPerson> list = jdbc.query(SQL, (rs, i) -> new ExpectedPerson(
                        rs.getString("person_no"),
                        rs.getInt("subject_type") == SUBJECT_EMPLOYEE
                                ? PersonPayload.USER_TYPE_WHITE
                                : PersonPayload.USER_TYPE_VISITOR),
                gateId, GRANT_ACTIVE, ts, ts);
        return new ArrayList<>(list);
    }
}
