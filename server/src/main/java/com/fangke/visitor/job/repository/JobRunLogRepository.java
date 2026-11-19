package com.fangke.visitor.job.repository;

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

/**
 * 定时任务日志仓储：每个任务一次执行一条记录，运维可查历史与耗时。
 */
@Repository
public class JobRunLogRepository {

    private static final RowMapper<JobRunLog> MAPPER = new RowMapper<>() {
        @Override
        public JobRunLog mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new JobRunLog(rs.getLong("id"), rs.getString("job_code"),
                    toLocal(rs.getTimestamp("started_at")),
                    toLocal(rs.getTimestamp("finished_at")),
                    rs.getInt("status"), rs.getInt("affected"), rs.getString("message"));
        }

        private LocalDateTime toLocal(Timestamp ts) {
            return ts == null ? null : ts.toLocalDateTime();
        }
    };

    private final JdbcTemplate jdbc;

    public JobRunLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String jobCode, LocalDateTime at) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO job_run_log (job_code, started_at, status, affected) VALUES (?, ?, 0, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, jobCode);
            ps.setTimestamp(2, Timestamp.valueOf(at));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("任务日志写入未返回主键");
        }
        return key.longValue();
    }

    public void finish(Long id, int status, int affected, String message, LocalDateTime at) {
        jdbc.update("UPDATE job_run_log SET status = ?, affected = ?, message = ?, finished_at = ? WHERE id = ?",
                status, affected,
                message == null ? null : message.substring(0, Math.min(message.length(), 500)),
                Timestamp.valueOf(at), id);
    }

    public List<JobRunLog> list(String jobCode, int offset, int limit) {
        if (jobCode == null || jobCode.isBlank()) {
            return jdbc.query("SELECT id, job_code, started_at, finished_at, status, affected, message "
                    + "FROM job_run_log ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, limit, offset);
        }
        return jdbc.query("SELECT id, job_code, started_at, finished_at, status, affected, message "
                        + "FROM job_run_log WHERE job_code = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                MAPPER, jobCode.trim(), limit, offset);
    }

    public long count(String jobCode) {
        Long count = jobCode == null || jobCode.isBlank()
                ? jdbc.queryForObject("SELECT COUNT(1) FROM job_run_log", Long.class)
                : jdbc.queryForObject("SELECT COUNT(1) FROM job_run_log WHERE job_code = ?",
                Long.class, jobCode.trim());
        return count == null ? 0 : count;
    }

    /** 保留期清理（默认 30 天）。 */
    public int deleteBefore(LocalDateTime time) {
        return jdbc.update("DELETE FROM job_run_log WHERE started_at < ?", Timestamp.valueOf(time));
    }
}
