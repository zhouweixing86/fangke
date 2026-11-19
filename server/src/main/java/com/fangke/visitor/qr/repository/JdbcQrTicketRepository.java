package com.fangke.visitor.qr.repository;

import com.fangke.visitor.qr.model.QrTicket;
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
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcQrTicketRepository implements QrTicketRepository {

    private static final String SELECT = """
            SELECT id, appointment_id, appointment_no, nonce, code_hash, status, use_count,
                   issued_channel, issued_by, issued_at, expire_at, last_used_at, verified_by
            FROM qr_ticket
            """;

    private static final RowMapper<QrTicket> MAPPER = new RowMapper<>() {
        @Override
        public QrTicket mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new QrTicket(
                    rs.getLong("id"),
                    rs.getLong("appointment_id"),
                    rs.getString("appointment_no"),
                    rs.getString("nonce"),
                    rs.getString("code_hash"),
                    rs.getInt("status"),
                    rs.getInt("use_count"),
                    rs.getString("issued_channel"),
                    nullableLong(rs, "issued_by"),
                    toLocal(rs.getTimestamp("issued_at")),
                    toLocal(rs.getTimestamp("expire_at")),
                    toLocal(rs.getTimestamp("last_used_at")),
                    nullableLong(rs, "verified_by"));
        }

        private Long nullableLong(ResultSet rs, String column) throws SQLException {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        }

        private LocalDateTime toLocal(Timestamp ts) {
            return ts == null ? null : ts.toLocalDateTime();
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcQrTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(QrTicket ticket) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO qr_ticket (appointment_id, appointment_no, nonce, code_hash, status,
                        use_count, issued_channel, issued_by, issued_at, expire_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ticket.appointmentId());
            ps.setString(2, ticket.appointmentNo());
            ps.setString(3, ticket.nonce());
            ps.setString(4, ticket.codeHash());
            ps.setInt(5, ticket.status());
            ps.setInt(6, ticket.useCount());
            ps.setString(7, ticket.issuedChannel());
            if (ticket.issuedBy() == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, ticket.issuedBy());
            }
            ps.setTimestamp(9, Timestamp.valueOf(ticket.issuedAt()));
            ps.setTimestamp(10, Timestamp.valueOf(ticket.expireAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("二维码票据写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public Optional<QrTicket> findByNonce(String nonce) {
        return jdbc.query(SELECT + " WHERE nonce = ? LIMIT 1", MAPPER, nonce).stream().findFirst();
    }

    @Override
    public int recordUse(Long id, Long verifiedBy, LocalDateTime at) {
        return jdbc.update("""
                        UPDATE qr_ticket SET use_count = use_count + 1, last_used_at = ?, verified_by = ?
                        WHERE id = ? AND status = ?
                        """,
                Timestamp.valueOf(at), verifiedBy, id, QrTicket.STATUS_VALID);
    }

    @Override
    public int revokeByAppointment(Long appointmentId) {
        return jdbc.update("UPDATE qr_ticket SET status = ? WHERE appointment_id = ? AND status = ?",
                QrTicket.STATUS_REVOKED, appointmentId, QrTicket.STATUS_VALID);
    }

    @Override
    public List<QrTicket> findValidByAppointment(Long appointmentId) {
        return jdbc.query(SELECT + " WHERE appointment_id = ? AND status = ? ORDER BY id DESC",
                MAPPER, appointmentId, QrTicket.STATUS_VALID);
    }

    @Override
    public int markExpired(LocalDateTime now) {
        return jdbc.update("UPDATE qr_ticket SET status = ? WHERE status = ? AND expire_at < ?",
                QrTicket.STATUS_EXPIRED, QrTicket.STATUS_VALID, Timestamp.valueOf(now));
    }

    @Override
    public int deleteBefore(LocalDateTime time) {
        return jdbc.update("DELETE FROM qr_ticket WHERE issued_at < ?", Timestamp.valueOf(time));
    }
}
