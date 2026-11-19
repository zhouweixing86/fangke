package com.fangke.visitor.employee.repository;

import com.fangke.visitor.employee.model.EmployeeFace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcEmployeeFaceRepository implements EmployeeFaceRepository {

    private static final RowMapper<EmployeeFace> MAPPER = new RowMapper<>() {
        @Override
        public EmployeeFace mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EmployeeFace(
                    rs.getLong("id"),
                    rs.getLong("employee_id"),
                    rs.getString("oss_key"),
                    rs.getString("oss_key_std"),
                    nullableInt(rs, "width"),
                    nullableInt(rs, "height"),
                    nullableInt(rs, "size_bytes"),
                    rs.getString("sha256"),
                    rs.getInt("is_current") == 1,
                    // uploaded_by 为 BIGINT UNSIGNED，MySQL 驱动返回 BigInteger，必须按基本类型读取
                    nullableLong(rs, "uploaded_by"),
                    rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());
        }
    };

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private final JdbcTemplate jdbc;

    public JdbcEmployeeFaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(EmployeeFace face) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO employee_face (employee_id, oss_key, oss_key_std, width, height, size_bytes,
                        sha256, is_current, uploaded_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, face.employeeId());
            ps.setString(2, face.ossKey());
            ps.setString(3, face.ossKeyStd());
            setNullableInt(ps, 4, face.width());
            setNullableInt(ps, 5, face.height());
            setNullableInt(ps, 6, face.sizeBytes());
            ps.setString(7, face.sha256());
            ps.setInt(8, face.current() ? 1 : 0);
            if (face.uploadedBy() == null) {
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setLong(9, face.uploadedBy());
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("照片记录写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void deactivateOthers(Long employeeId) {
        jdbc.update("UPDATE employee_face SET is_current = 0 WHERE employee_id = ?", employeeId);
    }

    @Override
    public Optional<EmployeeFace> findCurrent(Long employeeId) {
        return jdbc.query("SELECT * FROM employee_face WHERE employee_id = ? AND is_current = 1 "
                        + "ORDER BY id DESC LIMIT 1", MAPPER, employeeId)
                .stream().findFirst();
    }

    @Override
    public List<EmployeeFace> listByEmployee(Long employeeId) {
        return jdbc.query("SELECT * FROM employee_face WHERE employee_id = ? ORDER BY id DESC",
                MAPPER, employeeId);
    }

    private void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
