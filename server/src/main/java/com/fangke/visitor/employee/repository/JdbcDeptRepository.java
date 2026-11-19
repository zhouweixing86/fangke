package com.fangke.visitor.employee.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcDeptRepository implements DeptRepository {

    private static final String SELECT = """
            SELECT d.id, d.dept_code, d.dept_name, d.parent_id, d.sort_no,
                   (SELECT COUNT(1) FROM employee e WHERE e.dept_id = d.id) AS employee_count
            FROM dept d
            WHERE d.deleted_at IS NULL
            """;

    private static final org.springframework.jdbc.core.RowMapper<Dept> MAPPER = (rs, i) ->
            new Dept(rs.getLong("id"), rs.getString("dept_code"), rs.getString("dept_name"),
                    rs.getLong("parent_id"), rs.getInt("sort_no"), rs.getLong("employee_count"));

    private final JdbcTemplate jdbc;

    public JdbcDeptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Dept> findAll() {
        return jdbc.query(SELECT + " ORDER BY d.sort_no, d.id", MAPPER);
    }

    @Override
    public Optional<Dept> findById(Long id) {
        return jdbc.query(SELECT + " AND d.id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Long findIdByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        List<Long> ids = jdbc.query("SELECT id FROM dept WHERE dept_code = ? AND deleted_at IS NULL",
                (rs, i) -> rs.getLong(1), code.trim());
        return ids.stream().findFirst().orElse(null);
    }

    @Override
    public long insert(String code, String name, Long parentId, int sortNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO dept (dept_code, dept_name, parent_id, sort_no)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setLong(3, parentId == null ? 0L : parentId);
            ps.setInt(4, sortNo);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("部门写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void update(Long id, String name, Long parentId, int sortNo) {
        jdbc.update("UPDATE dept SET dept_name = ?, parent_id = ?, sort_no = ? WHERE id = ? AND deleted_at IS NULL",
                name, parentId == null ? 0L : parentId, sortNo, id);
    }

    @Override
    public void softDelete(Long id) {
        jdbc.update("UPDATE dept SET deleted_at = NOW(3) WHERE id = ? AND deleted_at IS NULL", id);
    }

    @Override
    public boolean existsCode(String code, Long excludeId) {
        Integer count = excludeId == null
                ? jdbc.queryForObject("SELECT COUNT(1) FROM dept WHERE dept_code = ? AND deleted_at IS NULL",
                        Integer.class, code)
                : jdbc.queryForObject("SELECT COUNT(1) FROM dept WHERE dept_code = ? AND id <> ? "
                        + "AND deleted_at IS NULL", Integer.class, code, excludeId);
        return count != null && count > 0;
    }
}
