package com.fangke.visitor.vehicle.cloud;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/** {@code vehicle_plate_push} 的 JDBC 实现。 */
@Repository
public class JdbcVehiclePlatePushRepository implements VehiclePlatePushRepository {

    private static final RowMapper<VehiclePlatePush> MAPPER = new RowMapper<>() {
        @Override
        public VehiclePlatePush mapRow(ResultSet rs, int rowNum) throws SQLException {
            VehiclePlatePush row = new VehiclePlatePush();
            row.setPlateNo(rs.getString("plate_no"));
            row.setVisitorId(rs.getString("visitor_id"));
            row.setOwnerType(rs.getString("owner_type"));
            long ownerRef = rs.getLong("owner_ref");
            row.setOwnerRef(rs.wasNull() ? null : ownerRef);
            row.setPersonName(rs.getString("person_name"));
            row.setBeginTime(toLocal(rs.getTimestamp("begin_time")));
            row.setEndTime(toLocal(rs.getTimestamp("end_time")));
            row.setFingerprint(rs.getString("fingerprint"));
            row.setState(rs.getString("state"));
            row.setLastError(rs.getString("last_error"));
            row.setPushedAt(toLocal(rs.getTimestamp("pushed_at")));
            return row;
        }

        private static LocalDateTime toLocal(Timestamp ts) {
            return ts == null ? null : ts.toLocalDateTime();
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcVehiclePlatePushRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<VehiclePlatePush> findAll() {
        return jdbc.query("SELECT plate_no, visitor_id, owner_type, owner_ref, person_name, begin_time, end_time, "
                + "fingerprint, state, last_error, pushed_at FROM vehicle_plate_push ORDER BY plate_no", MAPPER);
    }

    @Override
    public void save(VehiclePlatePush row) {
        jdbc.update("""
                        INSERT INTO vehicle_plate_push (plate_no, visitor_id, owner_type, owner_ref, person_name,
                            begin_time, end_time, fingerprint, state, last_error, pushed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE visitor_id = VALUES(visitor_id), owner_type = VALUES(owner_type),
                            owner_ref = VALUES(owner_ref), person_name = VALUES(person_name),
                            begin_time = VALUES(begin_time), end_time = VALUES(end_time),
                            fingerprint = VALUES(fingerprint), state = VALUES(state),
                            last_error = VALUES(last_error), pushed_at = VALUES(pushed_at)
                        """,
                row.getPlateNo(), row.getVisitorId(), row.getOwnerType(), row.getOwnerRef(), row.getPersonName(),
                row.getBeginTime() == null ? null : Timestamp.valueOf(row.getBeginTime()),
                row.getEndTime() == null ? null : Timestamp.valueOf(row.getEndTime()),
                row.getFingerprint(), row.getState(), row.getLastError(),
                row.getPushedAt() == null ? null : Timestamp.valueOf(row.getPushedAt()));
    }

    @Override
    public void delete(String plateNo) {
        jdbc.update("DELETE FROM vehicle_plate_push WHERE plate_no = ?", plateNo);
    }

    @Override
    public void markFailed(String plateNo, String error) {
        String msg = error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error);
        jdbc.update("UPDATE vehicle_plate_push SET state = ?, last_error = ? WHERE plate_no = ?",
                VehiclePlatePush.STATE_FAILED, msg, plateNo);
    }
}
