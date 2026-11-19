package com.fangke.visitor.job.service;

import com.fangke.visitor.job.repository.JobRunLog;
import com.fangke.visitor.job.repository.JobRunLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 定时任务执行器：统一计时、记日志、吞异常。
 *
 * <p>任务失败只记 {@code job_run_log}，不影响下一个任务，也不中断 Spring 调度线程。
 */
@Service
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRunLogRepository repository;

    public JobRunner(JobRunLogRepository repository) {
        this.repository = repository;
    }

    public int run(String jobCode, Supplier<Integer> action) {
        LocalDateTime startedAt = LocalDateTime.now();
        Long logId = null;
        try {
            logId = repository.start(jobCode, startedAt);
            int affected = action.get();
            repository.finish(logId, JobRunLog.STATUS_SUCCESS, affected, null, LocalDateTime.now());
            if (affected > 0) {
                log.info("任务 {} 完成：处理 {} 条", jobCode, affected);
            }
            return affected;
        } catch (Exception e) {
            log.error("任务 {} 执行失败", jobCode, e);
            if (logId != null) {
                try {
                    repository.finish(logId, JobRunLog.STATUS_FAILED, 0, e.getMessage(), LocalDateTime.now());
                } catch (Exception ignored) {
                    // 日志写入失败也不能再抛，否则会打断调度
                }
            }
            return 0;
        }
    }
}
