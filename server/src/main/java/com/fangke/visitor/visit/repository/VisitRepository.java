package com.fangke.visitor.visit.repository;

import com.fangke.visitor.visit.model.VisitDict;
import com.fangke.visitor.visit.model.VisitRegister;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 来访登记 + 基础字典仓储（V26）。
 */
@Repository
public class VisitRepository {

    private final JdbcTemplate jdbc;

    public VisitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<VisitDict> DICT_MAPPER = (rs, i) -> new VisitDict(
            rs.getLong("id"), rs.getString("dict_type"), rs.getString("name"),
            rs.getInt("sort"), rs.getInt("status"), rs.getString("remark"));

    private static final RowMapper<VisitRegister> REG_MAPPER = (rs, i) -> {
        long area = rs.getLong("area_id");
        Long areaId = rs.wasNull() ? null : area;
        long cause = rs.getLong("cause_id");
        Long causeId = rs.wasNull() ? null : cause;
        return new VisitRegister(
                rs.getLong("id"),
                areaId,
                rs.getString("area_name"),
                causeId,
                rs.getString("cause_name"),
                rs.getString("visitor_name"),
                rs.getString("phone_mask"),
                rs.getString("plate_no"),
                rs.getString("company"),
                rs.getInt("person_count"),
                rs.getTimestamp("arrival_time").toLocalDateTime(),
                rs.getTimestamp("leave_time") == null ? null : rs.getTimestamp("leave_time").toLocalDateTime(),
                rs.getInt("status"),
                rs.getString("remark"),
                rs.getTimestamp("created_at").toLocalDateTime());
    };

    private static final String REG_SELECT = """
            SELECT r.id, r.area_id, a.name AS area_name, r.cause_id, c.name AS cause_name,
                   r.visitor_name, r.phone_mask, r.plate_no, r.company, r.person_count,
                   r.arrival_time, r.leave_time, r.status, r.remark, r.created_at
            FROM visit_register r
            LEFT JOIN visit_dict a ON a.id = r.area_id
            LEFT JOIN visit_dict c ON c.id = r.cause_id
            """;

    /* ---------------- 字典 ---------------- */

    public List<VisitDict> listDict(String type, boolean onlyEnabled) {
        return jdbc.query("SELECT * FROM visit_dict WHERE dict_type = ?"
                        + (onlyEnabled ? " AND status = 1 " : " ")
                        + "ORDER BY sort, id",
                DICT_MAPPER, type);
    }

    public Optional<VisitDict> findDict(Long id) {
        return jdbc.query("SELECT * FROM visit_dict WHERE id = ?", DICT_MAPPER, id).stream().findFirst();
    }

    public boolean existsDict(String type, String name, Long excludeId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(1) FROM visit_dict
                        WHERE dict_type = ? AND name = ? AND (? IS NULL OR id <> ?)
                        """, Integer.class, type, name, excludeId, excludeId);
        return count != null && count > 0;
    }

    public long insertDict(VisitDict dict) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO visit_dict (dict_type, name, sort, status, remark) VALUES (?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, dict.dictType());
            ps.setString(2, dict.name());
            ps.setInt(3, dict.sort());
            ps.setInt(4, dict.status());
            ps.setString(5, dict.remark());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("字典写入未返回主键");
        }
        return key.longValue();
    }

    public void updateDict(Long id, String name, int sort, int status, String remark) {
        jdbc.update("UPDATE visit_dict SET name = ?, sort = ?, status = ?, remark = ? WHERE id = ?",
                name, sort, status, remark, id);
    }

    public void deleteDict(Long id) {
        jdbc.update("DELETE FROM visit_dict WHERE id = ?", id);
    }

    /* ---------------- 来访登记 ---------------- */

    public List<VisitRegister> search(String keyword, Long areaId, Long causeId, Integer status,
                                      LocalDate from, LocalDate to, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = registerWhere(keyword, areaId, causeId, status, from, to, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(REG_SELECT + where + " ORDER BY r.arrival_time DESC, r.id DESC LIMIT ? OFFSET ?",
                REG_MAPPER, args.toArray());
    }

    public long count(String keyword, Long areaId, Long causeId, Integer status,
                      LocalDate from, LocalDate to) {
        List<Object> args = new ArrayList<>();
        String where = registerWhere(keyword, areaId, causeId, status, from, to, args);
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM visit_register r" + where,
                Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public Optional<VisitRegister> findRegister(Long id) {
        return jdbc.query(REG_SELECT + " WHERE r.id = ?", REG_MAPPER, id).stream().findFirst();
    }

    public long insertRegister(VisitRegister row, byte[] phoneEnc, Long operatorId) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO visit_register (area_id, cause_id, visitor_name, phone_enc, phone_mask,
                        plate_no, company, person_count, arrival_time, status, remark, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, row.areaId());
            ps.setObject(2, row.causeId());
            ps.setString(3, row.visitorName());
            ps.setBytes(4, phoneEnc);
            ps.setString(5, row.phoneMask());
            ps.setString(6, row.plateNo());
            ps.setString(7, row.company());
            ps.setInt(8, row.personCount());
            ps.setTimestamp(9, Timestamp.valueOf(row.arrivalTime()));
            ps.setString(10, row.remark());
            ps.setObject(11, operatorId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("来访登记写入未返回主键");
        }
        return key.longValue();
    }

    public void markLeave(Long id, LocalDateTime leaveTime) {
        jdbc.update("UPDATE visit_register SET leave_time = ?, status = 2 WHERE id = ? AND status = 1",
                Timestamp.valueOf(leaveTime), id);
    }

    private String registerWhere(String keyword, Long areaId, Long causeId, Integer status,
                                 LocalDate from, LocalDate to, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (r.visitor_name LIKE ? OR r.phone_mask LIKE ? OR r.plate_no LIKE ? "
                    + "OR r.company LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (areaId != null) {
            sb.append(" AND r.area_id = ?");
            args.add(areaId);
        }
        if (causeId != null) {
            sb.append(" AND r.cause_id = ?");
            args.add(causeId);
        }
        if (status != null) {
            sb.append(" AND r.status = ?");
            args.add(status);
        }
        if (from != null) {
            sb.append(" AND r.arrival_time >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sb.append(" AND r.arrival_time < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        return sb.toString();
    }
}
