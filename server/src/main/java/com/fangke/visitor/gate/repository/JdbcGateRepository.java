package com.fangke.visitor.gate.repository;

import com.fangke.visitor.gate.model.Gate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcGateRepository implements GateRepository {

    private static final String SELECT = "SELECT id, gate_code, gate_name, gate_type, status, sort_no, remark FROM gate ";

    private static final RowMapper<Gate> MAPPER = new RowMapper<>() {
        @Override
        public Gate mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Gate(
                    rs.getLong("id"),
                    rs.getString("gate_code"),
                    rs.getString("gate_name"),
                    rs.getInt("gate_type"),
                    rs.getInt("status"),
                    rs.getInt("sort_no"),
                    rs.getString("remark"));
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcGateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Gate> findAll() {
        return jdbc.query(SELECT + "ORDER BY sort_no, id", MAPPER);
    }

    @Override
    public Optional<Gate> findById(Long id) {
        return jdbc.query(SELECT + "WHERE id = ? LIMIT 1", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Gate> findByCode(String gateCode) {
        return jdbc.query(SELECT + "WHERE gate_code = ? LIMIT 1", MAPPER, gateCode).stream().findFirst();
    }

    @Override
    public long insert(Gate gate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO gate (gate_code, gate_name, gate_type, status, sort_no, remark)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, gate.gateCode());
            ps.setString(2, gate.gateName());
            ps.setInt(3, gate.gateType());
            ps.setInt(4, gate.status());
            ps.setInt(5, gate.sortNo());
            ps.setString(6, gate.remark());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("门点写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void update(Gate gate) {
        jdbc.update("UPDATE gate SET gate_name = ?, gate_type = ?, sort_no = ?, remark = ? WHERE id = ?",
                gate.gateName(), gate.gateType(), gate.sortNo(), gate.remark(), gate.id());
    }

    @Override
    public void updateStatus(Long id, int status) {
        jdbc.update("UPDATE gate SET status = ? WHERE id = ?", status, id);
    }

    @Override
    public boolean existsCode(String gateCode, Long excludeId) {
        Integer count = excludeId == null
                ? jdbc.queryForObject("SELECT COUNT(1) FROM gate WHERE gate_code = ?", Integer.class, gateCode)
                : jdbc.queryForObject("SELECT COUNT(1) FROM gate WHERE gate_code = ? AND id <> ?",
                        Integer.class, gateCode, excludeId);
        return count != null && count > 0;
    }
}
