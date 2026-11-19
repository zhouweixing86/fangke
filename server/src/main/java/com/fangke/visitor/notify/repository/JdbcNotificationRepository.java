package com.fangke.visitor.notify.repository;

import com.fangke.visitor.notify.model.Notification;
import org.springframework.dao.DuplicateKeyException;
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

@Repository
public class JdbcNotificationRepository implements NotificationRepository {

    private static final String SELECT = """
            SELECT id, receiver_type, receiver_id, channel, template_code, title, content,
                   biz_type, biz_no, status, read_at, sent_at, retry_count, error_msg, dedup_key, created_at
            FROM notification
            """;

    private static final RowMapper<Notification> MAPPER = new RowMapper<>() {
        @Override
        public Notification mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Notification(
                    rs.getLong("id"),
                    rs.getInt("receiver_type"),
                    rs.getLong("receiver_id"),
                    rs.getInt("channel"),
                    rs.getString("template_code"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getString("biz_type"),
                    rs.getString("biz_no"),
                    rs.getInt("status"),
                    toLocal(rs.getTimestamp("read_at")),
                    toLocal(rs.getTimestamp("sent_at")),
                    rs.getInt("retry_count"),
                    rs.getString("error_msg"),
                    rs.getString("dedup_key"),
                    toLocal(rs.getTimestamp("created_at")));
        }

        private LocalDateTime toLocal(Timestamp ts) {
            return ts == null ? null : ts.toLocalDateTime();
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long insertIfAbsent(Notification n) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO notification (receiver_type, receiver_id, channel, template_code, title,
                            content, biz_type, biz_no, status, sent_at, retry_count, error_msg, dedup_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, n.receiverType());
                ps.setLong(2, n.receiverId());
                ps.setInt(3, n.channel());
                ps.setString(4, n.templateCode());
                ps.setString(5, n.title());
                ps.setString(6, n.content());
                ps.setString(7, n.bizType());
                ps.setString(8, n.bizNo());
                ps.setInt(9, n.status());
                setNullableTs(ps, 10, n.sentAt());
                ps.setInt(11, n.retryCount());
                ps.setString(12, n.errorMsg());
                ps.setString(13, n.dedupKey());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (DuplicateKeyException e) {
            // 同一事件重复推送：直接忽略
            return null;
        }
    }

    @Override
    public Optional<Notification> findById(Long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<Notification> list(int receiverType, Long receiverId, Boolean unreadOnly,
                                   int offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT
                + " WHERE receiver_type = ? AND receiver_id = ? AND channel = "
                + Notification.CHANNEL_INAPP);
        List<Object> args = new ArrayList<>(List.of(receiverType, receiverId));
        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND read_at IS NULL");
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public long count(int receiverType, Long receiverId, Boolean unreadOnly) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM notification "
                + "WHERE receiver_type = ? AND receiver_id = ? AND channel = "
                + Notification.CHANNEL_INAPP);
        List<Object> args = new ArrayList<>(List.of(receiverType, receiverId));
        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND read_at IS NULL");
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public long unreadCount(int receiverType, Long receiverId) {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM notification "
                        + "WHERE receiver_type = ? AND receiver_id = ? AND read_at IS NULL AND channel = ?",
                Long.class, receiverType, receiverId, Notification.CHANNEL_INAPP);
        return count == null ? 0 : count;
    }

    @Override
    public int markRead(Long id, int receiverType, Long receiverId, LocalDateTime at) {
        return jdbc.update("UPDATE notification SET read_at = ? WHERE id = ? AND receiver_type = ? "
                        + "AND receiver_id = ? AND read_at IS NULL AND channel = ?",
                Timestamp.valueOf(at), id, receiverType, receiverId, Notification.CHANNEL_INAPP);
    }

    @Override
    public int markAllRead(int receiverType, Long receiverId, LocalDateTime at) {
        return jdbc.update("UPDATE notification SET read_at = ? WHERE receiver_type = ? "
                        + "AND receiver_id = ? AND read_at IS NULL AND channel = ?",
                Timestamp.valueOf(at), receiverType, receiverId, Notification.CHANNEL_INAPP);
    }

    @Override
    public void markSent(Long id, LocalDateTime at) {
        jdbc.update("UPDATE notification SET status = ?, sent_at = ?, error_msg = NULL WHERE id = ?",
                Notification.STATUS_SENT, Timestamp.valueOf(at), id);
    }

    @Override
    public void markFailed(Long id, String errorMsg) {
        jdbc.update("UPDATE notification SET status = ?, retry_count = retry_count + 1, error_msg = ? "
                        + "WHERE id = ?",
                Notification.STATUS_FAILED,
                errorMsg == null ? "发送失败" : errorMsg.substring(0, Math.min(errorMsg.length(), 255)),
                id);
    }

    @Override
    public List<Notification> findRetryable(int limit) {
        return jdbc.query(SELECT + " WHERE status = ? AND retry_count < 3 ORDER BY id LIMIT ?",
                MAPPER, Notification.STATUS_FAILED, limit);
    }

    @Override
    public List<Notification> listForAdmin(String templateCode, Integer receiverType, Integer status,
                                           int offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendAdminFilters(sql, args, templateCode, receiverType, status);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public long countForAdmin(String templateCode, Integer receiverType, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM notification WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendAdminFilters(sql, args, templateCode, receiverType, status);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private void appendAdminFilters(StringBuilder sql, List<Object> args, String templateCode,
                                    Integer receiverType, Integer status) {
        if (templateCode != null && !templateCode.isBlank()) {
            sql.append(" AND template_code = ?");
            args.add(templateCode.trim());
        }
        if (receiverType != null) {
            sql.append(" AND receiver_type = ?");
            args.add(receiverType);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
    }

    private void setNullableTs(PreparedStatement ps, int index, LocalDateTime value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(value));
        }
    }
}
