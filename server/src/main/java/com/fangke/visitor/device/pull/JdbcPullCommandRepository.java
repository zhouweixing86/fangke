package com.fangke.visitor.device.pull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * device_cmd 的轮询式实现。
 *
 * <p>status: 0 待执行 / 1 执行中 / 2 成功 / 3 失败 / 4 放弃。
 * payload 只存 {@code {"personNo":"..."}} 这类最小信息，名单详情在设备回来拉时现查，
 * 避免队列里躺着过期数据。
 */
@Repository
public class JdbcPullCommandRepository implements PullCommandRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RowMapper<Row> MAPPER_ROW = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Row(rs.getLong("id"), rs.getString("cmd_type"), rs.getInt("priority"),
                    rs.getString("payload"), rs.getInt("retry_count"));
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcPullCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long enqueue(Long deviceId, String cmdType, int priority, String payload, Long batchId) {
        String personNo = personNoOf(payload);
        if (personNo != null) {
            // 去重：同一设备同一动作同一人只保留一条未完成指令
            Long exists = jdbc.queryForObject("""
                            SELECT COUNT(1) FROM device_cmd
                            WHERE device_id = ? AND cmd_type = ? AND status IN (0, 1)
                              AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.personNo')) = ?
                            """, Long.class, deviceId, cmdType, personNo);
            if (exists != null && exists > 0) {
                return -1;
            }
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO device_cmd (device_id, cmd_type, priority, payload, status, retry_count, batch_id)
                    VALUES (?, ?, ?, ?, 0, 0, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, deviceId);
            ps.setString(2, cmdType);
            ps.setInt(3, priority);
            ps.setString(4, payload);
            if (batchId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, batchId);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1 : key.longValue();
    }

    @Override
    public List<Row> listPending(Long deviceId, String cmdType, int limit) {
        return jdbc.query("""
                        SELECT id, cmd_type, priority, payload, retry_count FROM device_cmd
                        WHERE device_id = ? AND cmd_type = ? AND status = 0
                        ORDER BY priority DESC, id
                        LIMIT ?
                        """, MAPPER_ROW, deviceId, cmdType, Math.max(1, limit));
    }

    @Override
    public List<Row> listDispatching(Long deviceId, String cmdType, int limit) {
        return jdbc.query("""
                        SELECT id, cmd_type, priority, payload, retry_count FROM device_cmd
                        WHERE device_id = ? AND cmd_type = ? AND status = 1
                        ORDER BY priority DESC, id
                        LIMIT ?
                        """, MAPPER_ROW, deviceId, cmdType, Math.max(1, limit));
    }

    @Override
    public boolean hasPending(Long deviceId, String cmdType) {        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_cmd WHERE device_id = ? AND cmd_type = ? AND status = 0",
                Long.class, deviceId, cmdType);
        return count != null && count > 0;
    }

    @Override
    public void markDispatching(List<Long> ids, LocalDateTime at) {
        for (Long id : ids) {
            jdbc.update("""
                            UPDATE device_cmd SET status = 1, started_at = ?, retry_count = retry_count + 1
                            WHERE id = ? AND status = 0
                            """, Timestamp.valueOf(at), id);
        }
    }

    @Override
    public void markDone(List<Long> ids, int status, String errorMsg) {
        for (Long id : ids) {
            jdbc.update("""
                            UPDATE device_cmd SET status = ?, finished_at = NOW(3), error_msg = ?
                            WHERE id = ? AND status IN (0, 1)
                            """, status, truncate(errorMsg), id);
        }
    }

    @Override
    public int markDoneByPerson(Long deviceId, String cmdType, String personNo, int status, String message) {
        return jdbc.update("""
                        UPDATE device_cmd SET status = ?, finished_at = NOW(3), error_msg = ?
                        WHERE device_id = ? AND cmd_type = ? AND status IN (0, 1)
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.personNo')) = ?
                        """, status, truncate(message), deviceId, cmdType, personNo);
    }

    @Override
    public int requeueStale(Long deviceId, LocalDateTime dispatchedBefore, int maxRetry) {
        // 超过重试上限：置失败，避免永远卡在「执行中」
        int expired = jdbc.update("""
                        UPDATE device_cmd SET status = 3, finished_at = NOW(3),
                            error_msg = COALESCE(error_msg, '设备未回执，超过重试上限')
                        WHERE device_id = ? AND status = 1 AND started_at < ? AND retry_count >= ?
                        """, deviceId, Timestamp.valueOf(dispatchedBefore), Math.max(1, maxRetry));
        int requeued = jdbc.update("""
                        UPDATE device_cmd SET status = 0, started_at = NULL
                        WHERE device_id = ? AND status = 1 AND started_at < ? AND retry_count < ?
                        """, deviceId, Timestamp.valueOf(dispatchedBefore), Math.max(1, maxRetry));
        return expired + requeued;
    }

    @Override
    public long countPending(Long deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_cmd WHERE device_id = ? AND status IN (0, 1)",
                Long.class, deviceId);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Row> findById(Long id) {
        return jdbc.query("SELECT id, cmd_type, priority, payload, retry_count FROM device_cmd WHERE id = ?",
                MAPPER_ROW, id).stream().findFirst();
    }

    @Override
    public List<HistoryRow> listRecent(Long deviceId, int limit) {
        return jdbc.query("""
                        SELECT id, cmd_type, status, retry_count, error_msg, created_at, started_at, finished_at
                        FROM device_cmd WHERE device_id = ?
                        ORDER BY id DESC LIMIT ?
                        """,
                (rs, rowNum) -> new HistoryRow(rs.getLong("id"), rs.getString("cmd_type"), rs.getInt("status"),
                        rs.getInt("retry_count"), rs.getString("error_msg"),
                        toLocal(rs.getTimestamp("created_at")), toLocal(rs.getTimestamp("started_at")),
                        toLocal(rs.getTimestamp("finished_at"))),
                deviceId, Math.max(1, limit));
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private String personNoOf(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode personNo = node.get("personNo");
            return personNo == null || personNo.isNull() ? null : personNo.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /** 心跳时按优先级挑下一个要下发的动作（供 service 使用）。 */
    public List<String> pendingTypesOrdered(Long deviceId) {
        List<String> types = new ArrayList<>();
        for (String type : List.of(TYPE_OPEN_DOOR, TYPE_SYNC_TIME, TYPE_PARAM_SYNC,
                TYPE_ADD_PERSON, TYPE_UPDATE_PERSON, TYPE_DEL_PERSON)) {
            if (hasPending(deviceId, type)) {
                types.add(type);
            }
        }
        return types;
    }
}
