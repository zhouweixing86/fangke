package com.fangke.visitor.employee.repository;

import com.fangke.visitor.employee.model.Employee;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcEmployeeRepository implements EmployeeRepository {

    private static final String SELECT = """
            SELECT e.id, e.person_no, e.emp_no, e.name, e.dept_id, d.dept_name, e.leader_id, e.title,
                   e.phone_enc, e.phone_hmac, e.phone_mask, e.id_card_enc, e.id_card_hmac, e.id_card_mask,
                   e.status, e.valid_from, e.valid_to, e.face_status, e.remark, e.plate_no,
                   e.created_at, e.updated_at
            FROM employee e
            LEFT JOIN dept d ON d.id = e.dept_id
            """;

    private static final RowMapper<Employee> MAPPER = new RowMapper<>() {
        @Override
        public Employee mapRow(ResultSet rs, int rowNum) throws SQLException {
            Employee e = new Employee();
            e.setId(rs.getLong("id"));
            e.setPersonNo(rs.getString("person_no"));
            e.setEmpNo(rs.getString("emp_no"));
            e.setName(rs.getString("name"));
            long deptId = rs.getLong("dept_id");
            e.setDeptId(rs.wasNull() ? null : deptId);
            e.setDeptName(rs.getString("dept_name"));
            long leaderId = rs.getLong("leader_id");
            e.setLeaderId(rs.wasNull() ? null : leaderId);
            e.setTitle(rs.getString("title"));
            e.setPhoneEnc(rs.getBytes("phone_enc"));
            e.setPhoneHmac(rs.getString("phone_hmac"));
            e.setPhoneMask(rs.getString("phone_mask"));
            e.setIdCardEnc(rs.getBytes("id_card_enc"));
            e.setIdCardHmac(rs.getString("id_card_hmac"));
            e.setIdCardMask(rs.getString("id_card_mask"));
            e.setStatus(rs.getInt("status"));
            Date from = rs.getDate("valid_from");
            e.setValidFrom(from == null ? null : from.toLocalDate());
            Date to = rs.getDate("valid_to");
            e.setValidTo(to == null ? null : to.toLocalDate());
            e.setFaceStatus(rs.getInt("face_status"));
            e.setRemark(rs.getString("remark"));
            e.setPlateNo(rs.getString("plate_no"));
            if (rs.getTimestamp("created_at") != null) {
                e.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }
            if (rs.getTimestamp("updated_at") != null) {
                e.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            }
            return e;
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcEmployeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(Employee e) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO employee (person_no, emp_no, name, dept_id, leader_id, title,
                        phone_enc, phone_hmac, phone_mask, id_card_enc, id_card_hmac, id_card_mask,
                        status, valid_from, valid_to, face_status, remark, plate_no)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, e.getPersonNo());
            ps.setString(i++, e.getEmpNo());
            ps.setString(i++, e.getName());
            setNullableLong(ps, i++, e.getDeptId());
            setNullableLong(ps, i++, e.getLeaderId());
            ps.setString(i++, e.getTitle());
            setNullableBytes(ps, i++, e.getPhoneEnc());
            ps.setString(i++, e.getPhoneHmac());
            ps.setString(i++, e.getPhoneMask());
            setNullableBytes(ps, i++, e.getIdCardEnc());
            ps.setString(i++, e.getIdCardHmac());
            ps.setString(i++, e.getIdCardMask());
            ps.setInt(i++, e.getStatus());
            setNullableDate(ps, i++, e.getValidFrom());
            setNullableDate(ps, i++, e.getValidTo());
            ps.setInt(i++, e.getFaceStatus());
            ps.setString(i++, e.getRemark());
            ps.setString(i, e.getPlateNo());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("员工写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void update(Employee e) {
        jdbc.update("""
                        UPDATE employee SET name = ?, dept_id = ?, leader_id = ?, title = ?,
                            phone_enc = ?, phone_hmac = ?, phone_mask = ?,
                            id_card_enc = ?, id_card_hmac = ?, id_card_mask = ?,
                            status = ?, valid_from = ?, valid_to = ?, remark = ?, plate_no = ?
                        WHERE id = ?
                        """,
                e.getName(), e.getDeptId(), e.getLeaderId(), e.getTitle(),
                e.getPhoneEnc(), e.getPhoneHmac(), e.getPhoneMask(),
                e.getIdCardEnc(), e.getIdCardHmac(), e.getIdCardMask(),
                e.getStatus(), e.getValidFrom(), e.getValidTo(), e.getRemark(), e.getPlateNo(),
                e.getId());
    }

    @Override
    public Optional<Employee> findById(Long id) {
        return jdbc.query(SELECT + " WHERE e.id = ? AND e.deleted_at IS NULL", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<Employee> findByEmpNo(String empNo) {
        return jdbc.query(SELECT + " WHERE e.emp_no = ? AND e.deleted_at IS NULL", MAPPER, empNo)
                .stream().findFirst();
    }

    @Override
    public Optional<Employee> findByPhoneHmac(String phoneHmac) {
        if (phoneHmac == null) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " WHERE e.phone_hmac = ? AND e.deleted_at IS NULL AND e.status = 1", MAPPER, phoneHmac)
                .stream().findFirst();
    }

    @Override
    public boolean existsEmpNo(String empNo, Long excludeId) {
        return countWhere("emp_no = ?", empNo, excludeId) > 0;
    }

    @Override
    public boolean existsPhoneHmac(String phoneHmac, Long excludeId) {
        return countWhere("phone_hmac = ?", phoneHmac, excludeId) > 0;
    }

    @Override
    public boolean existsIdCardHmac(String idCardHmac, Long excludeId) {
        return countWhere("id_card_hmac = ?", idCardHmac, excludeId) > 0;
    }

    private long countWhere(String condition, String value, Long excludeId) {
        String sql = "SELECT COUNT(1) FROM employee WHERE " + condition + " AND deleted_at IS NULL"
                + (excludeId == null ? "" : " AND id <> ?");
        Long count = excludeId == null
                ? jdbc.queryForObject(sql, Long.class, value)
                : jdbc.queryForObject(sql, Long.class, value, excludeId);
        return count == null ? 0 : count;
    }

    @Override
    public List<Employee> search(String keyword, Integer status, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(keyword, status, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(SELECT + where + " ORDER BY e.id DESC LIMIT ? OFFSET ?", MAPPER, args.toArray());
    }

    @Override
    public long count(String keyword, Integer status) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(keyword, status, args);
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM employee e" + where, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private String buildWhere(String keyword, Integer status, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE e.deleted_at IS NULL");
        if (status != null) {
            sb.append(" AND e.status = ?");
            args.add(status);
        }
        if (StringUtils.hasText(keyword)) {
            sb.append(" AND (e.emp_no LIKE ? OR e.person_no LIKE ? OR e.name LIKE ?"
                    + " OR e.phone_mask LIKE ? OR e.id_card_mask LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }
        return sb.toString();
    }

    @Override
    public void updateStatus(Long id, int status) {
        jdbc.update("UPDATE employee SET status = ? WHERE id = ?", status, id);
    }

    @Override
    public void updateFaceStatus(Long id, int faceStatus) {
        jdbc.update("UPDATE employee SET face_status = ? WHERE id = ?", faceStatus, id);
    }

    @Override
    public List<Employee> findExpiringBefore(LocalDate deadline) {
        return jdbc.query(SELECT + " WHERE e.deleted_at IS NULL AND e.status = 1 "
                + "AND e.valid_to IS NOT NULL AND e.valid_to <= ? ORDER BY e.valid_to", MAPPER, deadline);
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

    private void setNullableDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(value));
        }
    }
    @Override
    public Optional<Employee> findActiveByPlateNo(String plateNo) {
        if (plateNo == null || plateNo.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " WHERE e.plate_no = ? AND e.status = 1 AND e.deleted_at IS NULL "
                                + "ORDER BY e.id DESC LIMIT 1",
                        MAPPER, plateNo.replace(" ", "").trim().toUpperCase())
                .stream().findFirst();
    }

}
