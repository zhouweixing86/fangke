package com.fangke.visitor.dispatch.repository;

import com.fangke.visitor.dispatch.model.SyncBatch;
import com.fangke.visitor.dispatch.model.SyncBatchItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSyncBatchRepository implements SyncBatchRepository {

    private final JdbcTemplate jdbc;

    public JdbcSyncBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(SyncBatch batch) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sync_batch (batch_no, trigger_type, gate_id, status, started_at)
                    VALUES (?, ?, ?, 0, NOW(3))
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batch.batchNo());
            ps.setInt(2, batch.triggerType());
            ps.setLong(3, batch.gateId());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("批次写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void start(Long id) {
        jdbc.update("UPDATE sync_batch SET started_at = NOW(3) WHERE id = ?", id);
    }

    @Override
    public void finish(Long id, int total, int success, int fail, int status) {
        jdbc.update("UPDATE sync_batch SET total_count = ?, success_count = ?, fail_count = ?, status = ?, "
                + "finished_at = NOW(3) WHERE id = ?", total, success, fail, status, id);
    }

    @Override
    public long addItem(SyncBatchItem item) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sync_batch_item (batch_id, device_id, person_no, action, status)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, item.batchId());
            ps.setLong(2, item.deviceId());
            ps.setString(3, item.personNo());
            ps.setInt(4, item.action());
            ps.setInt(5, item.status());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("批次明细写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateItem(Long id, int status, String errorCode, String errorMsg) {
        jdbc.update("UPDATE sync_batch_item SET status = ?, error_code = ?, error_msg = ?, acked_at = NOW(3) "
                + "WHERE id = ?", status, errorCode, errorMsg, id);
    }

    @Override
    public Optional<SyncBatch> findById(Long id) {
        return jdbc.query("""
                        SELECT id, batch_no, trigger_type, gate_id, total_count, success_count, fail_count, status,
                               started_at, finished_at
                        FROM sync_batch WHERE id = ?
                        """,
                (rs, i) -> new SyncBatch(
                        rs.getLong("id"), rs.getString("batch_no"), rs.getInt("trigger_type"),
                        rs.getLong("gate_id"), rs.getInt("total_count"), rs.getInt("success_count"),
                        rs.getInt("fail_count"), rs.getInt("status"),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime()),
                id).stream().findFirst();
    }

    @Override
    public List<SyncBatch> list(int offset, int limit) {
        return jdbc.query("""
                        SELECT id, batch_no, trigger_type, gate_id, total_count, success_count, fail_count, status,
                               started_at, finished_at
                        FROM sync_batch ORDER BY id DESC LIMIT ? OFFSET ?
                        """,
                (rs, i) -> new SyncBatch(
                        rs.getLong("id"), rs.getString("batch_no"), rs.getInt("trigger_type"),
                        rs.getLong("gate_id"), rs.getInt("total_count"), rs.getInt("success_count"),
                        rs.getInt("fail_count"), rs.getInt("status"),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime()),
                limit, offset);
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM sync_batch", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<SyncBatchItem> items(Long batchId, int offset, int limit) {
        return jdbc.query("""
                        SELECT id, batch_id, device_id, person_no, action, status, error_code, error_msg, acked_at
                        FROM sync_batch_item WHERE batch_id = ? ORDER BY id LIMIT ? OFFSET ?
                        """,
                (rs, i) -> new SyncBatchItem(
                        rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("device_id"),
                        rs.getString("person_no"), rs.getInt("action"), rs.getInt("status"),
                        rs.getString("error_code"), rs.getString("error_msg"),
                        rs.getTimestamp("acked_at") == null ? null : rs.getTimestamp("acked_at").toLocalDateTime()),
                batchId, limit, offset);
    }

    @Override
    public long countItems(Long batchId) {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM sync_batch_item WHERE batch_id = ?",
                Long.class, batchId);
        return count == null ? 0 : count;
    }
}
