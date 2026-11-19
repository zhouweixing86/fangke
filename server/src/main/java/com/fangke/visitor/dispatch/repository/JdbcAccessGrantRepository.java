package com.fangke.visitor.dispatch.repository;

import com.fangke.visitor.dispatch.model.AccessGrant;
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
public class JdbcAccessGrantRepository implements AccessGrantRepository {

    private static final String SELECT = """
            SELECT id, subject_type, subject_id, person_no, appointment_id, gate_id, valid_from, valid_to, status
            FROM access_grant
            """;

    private static final RowMapper<AccessGrant> MAPPER = new RowMapper<>() {
        @Override
        public AccessGrant mapRow(ResultSet rs, int rowNum) throws SQLException {
            long appointmentId = rs.getLong("appointment_id");
            Long appointment = rs.wasNull() ? null : appointmentId;
            return new AccessGrant(
                    rs.getLong("id"),
                    rs.getInt("subject_type"),
                    rs.getLong("subject_id"),
                    rs.getString("person_no"),
                    appointment,
                    rs.getLong("gate_id"),
                    rs.getTimestamp("valid_from").toLocalDateTime(),
                    rs.getTimestamp("valid_to").toLocalDateTime(),
                    rs.getInt("status"));
        }
    };

    @Override
    public Optional<AccessGrant> findLatestByPersonNo(String personNo) {
        return jdbc.query(SELECT + " WHERE person_no = ? ORDER BY id DESC LIMIT 1", MAPPER, personNo)
                .stream().findFirst();
    }

    @Override
    public List<AccessGrant> search(Integer subjectType, Integer status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, subjectType, status);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public long count(Integer subjectType, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM access_grant WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, subjectType, status);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private void appendFilters(StringBuilder sql, List<Object> args, Integer subjectType, Integer status) {
        if (subjectType != null) {
            sql.append(" AND subject_type = ?");
            args.add(subjectType);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
    }

    private final JdbcTemplate jdbc;

    public JdbcAccessGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccessGrant> findByAppointmentId(Long appointmentId) {
        return jdbc.query(SELECT + " WHERE appointment_id = ? ORDER BY id DESC LIMIT 1", MAPPER, appointmentId)
                .stream().findFirst();
    }

    @Override
    public Optional<AccessGrant> findByAppointmentIdAndGate(Long appointmentId, Long gateId) {
        return jdbc.query(SELECT + " WHERE appointment_id = ? AND gate_id = ? ORDER BY id DESC LIMIT 1",
                        MAPPER, appointmentId, gateId)
                .stream().findFirst();
    }

    @Override
    public List<AccessGrant> findAllByAppointmentId(Long appointmentId) {
        return jdbc.query(SELECT + " WHERE appointment_id = ? ORDER BY gate_id, id", MAPPER, appointmentId);
    }

    @Override
    public List<AccessGrant> findBySubject(int subjectType, Long subjectId) {
        return jdbc.query(SELECT + " WHERE subject_type = ? AND subject_id = ? ORDER BY gate_id, id",
                MAPPER, subjectType, subjectId);
    }

    @Override
    public Optional<AccessGrant> findBySubjectAndGate(int subjectType, Long subjectId, Long gateId) {
        return jdbc.query(SELECT + " WHERE subject_type = ? AND subject_id = ? AND gate_id = ? "
                        + "ORDER BY id DESC LIMIT 1", MAPPER, subjectType, subjectId, gateId)
                .stream().findFirst();
    }

    @Override
    public long insert(AccessGrant g) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO access_grant (subject_type, subject_id, person_no, appointment_id, gate_id,
                        valid_from, valid_to, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, g.subjectType());
            ps.setLong(2, g.subjectId());
            ps.setString(3, g.personNo());
            // 员工授权没有预约单（C-66）：appointment_id 允许 NULL
            if (g.appointmentId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, g.appointmentId());
            }
            ps.setLong(5, g.gateId());
            ps.setTimestamp(6, Timestamp.valueOf(g.validFrom()));
            ps.setTimestamp(7, Timestamp.valueOf(g.validTo()));
            ps.setInt(8, g.status());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("授权写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateStatus(Long id, int status) {
        jdbc.update("UPDATE access_grant SET status = ? WHERE id = ?", status, id);
    }

    @Override
    public void revoke(Long id, Long operatorId) {
        jdbc.update("UPDATE access_grant SET status = 4, revoked_at = NOW(3), revoked_by = ? WHERE id = ?",
                operatorId, id);
    }

    @Override
    public List<AccessGrant> findExpiredBefore(LocalDateTime before, int limit) {
        return jdbc.query(SELECT + " WHERE status IN (1,5) AND valid_to < ? ORDER BY valid_to LIMIT ?",
                MAPPER, Timestamp.valueOf(before), limit);
    }
}
