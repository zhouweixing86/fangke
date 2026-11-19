package com.fangke.visitor.device.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPersonStateStore implements PersonStateStore {

    private static final String UPSERT = """
            INSERT INTO person_device_state
                (device_id, person_no, expect_state, actual_state, last_checked_at, diff_flag)
            VALUES (?, ?, ?, ?, NOW(3), ?)
            ON DUPLICATE KEY UPDATE
                expect_state = VALUES(expect_state),
                actual_state = VALUES(actual_state),
                last_checked_at = VALUES(last_checked_at),
                diff_flag = VALUES(diff_flag)
            """;

    private final JdbcTemplate jdbc;

    public JdbcPersonStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Long deviceId, String personNo, int expectState, int actualState, boolean diff) {
        jdbc.update(UPSERT, deviceId, personNo, expectState, actualState, diff ? 1 : 0);
    }
}
