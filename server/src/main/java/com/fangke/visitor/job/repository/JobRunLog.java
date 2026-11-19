package com.fangke.visitor.job.repository;

import java.time.LocalDateTime;

/**
 * 定时任务执行记录（docs/10 job_run_log）。
 */
public record JobRunLog(Long id,
                        String jobCode,
                        LocalDateTime startedAt,
                        LocalDateTime finishedAt,
                        int status,
                        int affected,
                        String message) {

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    public static String statusText(int status) {
        return switch (status) {
            case STATUS_RUNNING -> "运行中";
            case STATUS_SUCCESS -> "成功";
            case STATUS_FAILED -> "失败";
            default -> "未知";
        };
    }
}
