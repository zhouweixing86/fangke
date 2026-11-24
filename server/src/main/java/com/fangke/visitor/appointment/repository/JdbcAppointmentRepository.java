package com.fangke.visitor.appointment.repository;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
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
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAppointmentRepository implements AppointmentRepository {

    private static final String SELECT = """
            SELECT id, appointment_no, source, visitor_id, visit_employee_id, visit_phone_enc, visit_phone_mask,
                   visitor_type, purpose, area_id, company, position, period, plate_no, department, gate_id, gate_scope,
                   start_time, end_time,
                   mate_mode, mate_count, qr_requested, qr_approved, qr_approved_by, qr_approved_at,
                   approval_level, status, reject_reason, cancel_reason,
                   current_task_id, granted_at, created_at
            FROM appointment
            """;

    private static final RowMapper<Appointment> MAPPER = new RowMapper<>() {
        @Override
        public Appointment mapRow(ResultSet rs, int rowNum) throws SQLException {
            Appointment a = new Appointment();
            a.setId(rs.getLong("id"));
            a.setAppointmentNo(rs.getString("appointment_no"));
            a.setSource(rs.getInt("source"));
            a.setVisitorId(rs.getLong("visitor_id"));
            long empId = rs.getLong("visit_employee_id");
            a.setVisitEmployeeId(rs.wasNull() ? null : empId);
            a.setVisitPhoneEnc(rs.getBytes("visit_phone_enc"));
            a.setVisitPhoneMask(rs.getString("visit_phone_mask"));
            a.setVisitorType(rs.getString("visitor_type"));
            a.setPurpose(rs.getString("purpose"));
            long areaId = rs.getLong("area_id");
            a.setAreaId(rs.wasNull() ? null : areaId);
            a.setCompany(rs.getString("company"));
            a.setPosition(rs.getString("position"));
            a.setPeriod(rs.getInt("period"));
            a.setPlateNo(rs.getString("plate_no"));
            a.setDepartment(rs.getString("department"));
            long gateId = rs.getLong("gate_id");
            a.setGateId(rs.wasNull() ? null : gateId);
            a.setGateScope(rs.getInt("gate_scope"));
            a.setStartTime(rs.getTimestamp("start_time").toLocalDateTime());
            a.setEndTime(rs.getTimestamp("end_time").toLocalDateTime());
            a.setMateMode(rs.getInt("mate_mode"));
            a.setMateCount(rs.getInt("mate_count"));
            a.setQrRequested(rs.getBoolean("qr_requested"));
            a.setQrApproved(rs.getBoolean("qr_approved"));
            long qrBy = rs.getLong("qr_approved_by");
            a.setQrApprovedBy(rs.wasNull() ? null : qrBy);
            java.sql.Timestamp qrAt = rs.getTimestamp("qr_approved_at");
            a.setQrApprovedAt(qrAt == null ? null : qrAt.toLocalDateTime());
            a.setApprovalLevel(rs.getInt("approval_level"));
            a.setStatus(rs.getInt("status"));
            a.setRejectReason(rs.getString("reject_reason"));
            a.setCancelReason(rs.getString("cancel_reason"));
            long taskId = rs.getLong("current_task_id");
            a.setCurrentTaskId(rs.wasNull() ? null : taskId);
            if (rs.getTimestamp("granted_at") != null) {
                a.setGrantedAt(rs.getTimestamp("granted_at").toLocalDateTime());
            }
            if (rs.getTimestamp("created_at") != null) {
                a.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }
            return a;
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcAppointmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(Appointment a) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO appointment (appointment_no, source, visitor_id, visit_employee_id,
                    visit_phone_enc, visit_phone_mask, visitor_type, purpose, area_id, company, position, period,
                    plate_no, department,
                        gate_id, gate_scope, start_time, end_time, mate_mode, mate_count,
                        qr_requested, qr_approved, qr_approved_by, qr_approved_at,
                        approval_level, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, a.getAppointmentNo());
            ps.setInt(i++, a.getSource());
            ps.setLong(i++, a.getVisitorId());
            setNullableLong(ps, i++, a.getVisitEmployeeId());
            setNullableBytes(ps, i++, a.getVisitPhoneEnc());
            ps.setString(i++, a.getVisitPhoneMask());
            ps.setString(i++, a.getVisitorType());
            ps.setString(i++, a.getPurpose());
            setNullableLong(ps, i++, a.getAreaId());
            ps.setString(i++, a.getCompany());
            ps.setString(i++, a.getPosition());
            ps.setInt(i++, a.getPeriod());
            ps.setString(i++, a.getPlateNo());
            ps.setString(i++, a.getDepartment());
            setNullableLong(ps, i++, a.getGateId());
            ps.setInt(i++, a.getGateScope());
            ps.setTimestamp(i++, Timestamp.valueOf(a.getStartTime()));
            ps.setTimestamp(i++, Timestamp.valueOf(a.getEndTime()));
            ps.setInt(i++, a.getMateMode());
            ps.setInt(i++, a.getMateCount());
            ps.setBoolean(i++, a.isQrRequested());
            ps.setBoolean(i++, a.isQrApproved());
            setNullableLong(ps, i++, a.getQrApprovedBy());
            ps.setTimestamp(i++, a.getQrApprovedAt() == null ? null : Timestamp.valueOf(a.getQrApprovedAt()));
            ps.setInt(i++, a.getApprovalLevel());
            ps.setInt(i, a.getStatus());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("预约单写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void update(Appointment a) {
        jdbc.update("""
                        UPDATE appointment SET visit_employee_id = ?, visit_phone_enc = ?, visit_phone_mask = ?,
                            visitor_type = ?, purpose = ?, area_id = ?, company = ?, position = ?, period = ?,
                            plate_no = ?, department = ?,
                            start_time = ?, end_time = ?, mate_mode = ?, mate_count = ?,
                            qr_requested = ?, qr_approved = ?, qr_approved_by = ?, qr_approved_at = ?,
                            approval_level = ?, status = ?, reject_reason = ?, cancel_reason = ?,
                            current_task_id = ?, granted_at = ?
                        WHERE id = ?
                        """,
                a.getVisitEmployeeId(), a.getVisitPhoneEnc(), a.getVisitPhoneMask(),
                a.getVisitorType(), a.getPurpose(), a.getAreaId(), a.getCompany(), a.getPosition(), a.getPeriod(),
                a.getPlateNo(), a.getDepartment(),
                Timestamp.valueOf(a.getStartTime()), Timestamp.valueOf(a.getEndTime()),
                a.getMateMode(), a.getMateCount(),
                a.isQrRequested(), a.isQrApproved(), a.getQrApprovedBy(),
                a.getQrApprovedAt() == null ? null : Timestamp.valueOf(a.getQrApprovedAt()),
                a.getApprovalLevel(), a.getStatus(),
                a.getRejectReason(), a.getCancelReason(), a.getCurrentTaskId(),
                a.getGrantedAt() == null ? null : Timestamp.valueOf(a.getGrantedAt()), a.getId());
    }

    @Override
    public void updateStatus(Long id, int status) {
        jdbc.update("UPDATE appointment SET status = ? WHERE id = ?", status, id);
    }

    @Override
    public Optional<Appointment> findByNo(String appointmentNo) {
        return jdbc.query(SELECT + " WHERE appointment_no = ?", MAPPER, appointmentNo).stream().findFirst();
    }

    @Override
    public Optional<Appointment> findById(Long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<Appointment> findByVisitor(Long visitorId, Integer status, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE visitor_id = ?");
        args.add(visitorId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public long countByVisitor(Long visitorId, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM appointment WHERE visitor_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(visitorId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<Appointment> findActiveByVisitor(Long visitorId) {
        return jdbc.query(SELECT + " WHERE visitor_id = ? AND status IN (20, 25, 30, 35, 40) "
                + "ORDER BY id", MAPPER, visitorId);
    }

    @Override
    public Optional<Appointment> findInProgress(Long visitorId) {
        return jdbc.query(SELECT + " WHERE visitor_id = ? AND status IN (10, 20, 25, 30, 35, 40) "
                + "ORDER BY id DESC LIMIT 1", MAPPER, visitorId).stream().findFirst();
    }

    @Override
    public void insertMate(AppointmentMate m) {
        jdbc.update("""
                        INSERT INTO appointment_mate (appointment_id, name, id_card_enc, id_card_hmac, id_card_mask,
                            phone_enc, phone_mask, is_main, person_no)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                m.appointmentId(), m.name(), m.idCardEnc(), m.idCardHmac(), m.idCardMask(),
                m.phoneEnc(), m.phoneMask(), m.main() ? 1 : 0, m.personNo());
    }

    @Override
    public List<AppointmentMate> findMates(Long appointmentId) {
        return jdbc.query("""
                        SELECT id, appointment_id, name, id_card_enc, id_card_hmac, id_card_mask,
                               phone_enc, phone_mask, is_main, person_no
                        FROM appointment_mate WHERE appointment_id = ? ORDER BY is_main DESC, id
                        """,
                (rs, i) -> new AppointmentMate(
                        rs.getLong("id"),
                        rs.getLong("appointment_id"),
                        rs.getString("name"),
                        rs.getBytes("id_card_enc"),
                        rs.getString("id_card_hmac"),
                        rs.getString("id_card_mask"),
                        rs.getBytes("phone_enc"),
                        rs.getString("phone_mask"),
                        rs.getInt("is_main") == 1,
                        rs.getString("person_no")),
                appointmentId);
    }

    @Override
    public Optional<String> findMaxNoByPrefix(String prefix) {
        List<String> list = jdbc.query("SELECT MAX(appointment_no) FROM appointment WHERE appointment_no LIKE ?",
                (rs, i) -> rs.getString(1), prefix + "%");
        return list.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public List<Long> findExpirableIds(java.time.LocalDateTime before, int limit) {
        return jdbc.query("SELECT id FROM appointment WHERE status IN (30, 35) AND end_time < ? "
                        + "ORDER BY end_time LIMIT ?",
                (rs, i) -> rs.getLong(1), java.sql.Timestamp.valueOf(before), limit);
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private void setNullableBytes(PreparedStatement ps, int index, byte[] value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARBINARY);
        } else {
            ps.setBytes(index, value);
        }
    }
    @Override
    public List<Appointment> findByPlateNoOnDate(String plateNo, LocalDate date) {
        if (plateNo == null || plateNo.isBlank() || date == null) {
            return List.of();
        }
        return jdbc.query(SELECT + " WHERE plate_no = ? AND status IN (35, 40) "
                                + "AND start_time >= ? AND start_time < ? ORDER BY id",
                MAPPER, plateNo.replace(" ", "").trim().toUpperCase(),
                Timestamp.valueOf(date.atStartOfDay()),
                Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

    @Override
    public List<Appointment> findPlateAppointmentsOnDate(LocalDate date) {
        if (date == null) {
            return List.of();
        }
        return jdbc.query(SELECT + " WHERE plate_no IS NOT NULL AND plate_no <> '' AND status IN (35, 40) "
                                + "AND start_time >= ? AND start_time < ? ORDER BY id",
                MAPPER, Timestamp.valueOf(date.atStartOfDay()),
                Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

}
