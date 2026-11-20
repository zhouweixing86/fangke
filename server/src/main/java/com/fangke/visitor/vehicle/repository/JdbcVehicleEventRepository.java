package com.fangke.visitor.vehicle.repository;

import com.fangke.visitor.vehicle.model.VehicleEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
public class JdbcVehicleEventRepository implements VehicleEventRepository {

    private static final String SELECT = """
            SELECT id, event_uuid, device_sn, gate_id, plate_no, direction, event_time, confidence,
                   matched_type, matched_id, matched_name, result_status, fail_reason, photo_key, raw_json
            FROM vehicle_event
            """;

    private static final RowMapper<VehicleEvent> MAPPER = (rs, i) -> {
        long gateId = rs.getLong("gate_id");
        Long gate = rs.wasNull() ? null : gateId;
        long matchedId = rs.getLong("matched_id");
        Long matched = rs.wasNull() ? null : matchedId;
        int type = rs.getInt("matched_type");
        Integer matchedType = rs.wasNull() ? null : type;
        int confidence = rs.getInt("confidence");
        Integer conf = rs.wasNull() ? null : confidence;
        return new VehicleEvent(
                rs.getLong("id"),
                rs.getString("event_uuid"),
                rs.getString("device_sn"),
                gate,
                rs.getString("plate_no"),
                rs.getInt("direction"),
                rs.getTimestamp("event_time").toLocalDateTime(),
                conf,
                matchedType,
                matched,
                rs.getString("matched_name"),
                rs.getInt("result_status"),
                rs.getString("fail_reason"),
                rs.getString("photo_key"),
                rs.getString("raw_json"));
    };

    private final JdbcTemplate jdbc;

    public JdbcVehicleEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(VehicleEvent e) {
        int rows = jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT IGNORE INTO vehicle_event (event_uuid, device_sn, gate_id, plate_no, direction,
                        event_time, confidence, matched_type, matched_id, matched_name, result_status,
                        fail_reason, photo_key, raw_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, e.eventUuid());
            ps.setString(2, e.deviceSn());
            if (e.gateId() == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, e.gateId());
            }
            ps.setString(4, e.plateNo());
            ps.setInt(5, e.direction());
            ps.setTimestamp(6, Timestamp.valueOf(e.eventTime()));
            if (e.confidence() == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, e.confidence());
            }
            if (e.matchedType() == null) {
                ps.setNull(8, Types.TINYINT);
            } else {
                ps.setInt(8, e.matchedType());
            }
            if (e.matchedId() == null) {
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setLong(9, e.matchedId());
            }
            ps.setString(10, e.matchedName());
            ps.setInt(11, e.resultStatus());
            ps.setString(12, e.failReason());
            ps.setString(13, e.photoKey());
            ps.setString(14, e.rawJson());
            return ps;
        });
        return rows > 0;
    }

    @Override
    public List<VehicleEvent> search(LocalDate from, LocalDate to, String plateNo, Long gateId,
                                     Integer resultStatus, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = where(from, to, plateNo, gateId, resultStatus, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(SELECT + where + " ORDER BY event_time DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, args.toArray());
    }

    @Override
    public long count(LocalDate from, LocalDate to, String plateNo, Long gateId, Integer resultStatus) {
        List<Object> args = new ArrayList<>();
        String where = where(from, to, plateNo, gateId, resultStatus, args);
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM vehicle_event " + where, Long.class,
                args.toArray());
        return count == null ? 0 : count;
    }

    private String where(LocalDate from, LocalDate to, String plateNo, Long gateId,
                         Integer resultStatus, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (from != null) {
            sql.append(" AND event_time >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND event_time < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        if (plateNo != null && !plateNo.isBlank()) {
            sql.append(" AND plate_no LIKE ?");
            args.add("%" + plateNo.trim().toUpperCase() + "%");
        }
        if (gateId != null) {
            sql.append(" AND gate_id = ?");
            args.add(gateId);
        }
        if (resultStatus != null) {
            sql.append(" AND result_status = ?");
            args.add(resultStatus);
        }
        return sql.toString();
    }
}
