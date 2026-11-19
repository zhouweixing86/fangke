package com.fangke.visitor.dispatch.service;

import com.fangke.visitor.job.service.JobCodes;
import com.fangke.visitor.job.service.JobRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期名单清理（docs/02 JOB-05，默认每日 03:00）。
 *
 * <p>过期 7 天以上的访客名单必须从设备删除，否则人员会长期留在可通行名单里。
 */
@Component
public class DispatchCleanupJob {

    private final DispatchService dispatchService;
    private final JobRunner jobRunner;

    public DispatchCleanupJob(DispatchService dispatchService, JobRunner jobRunner) {
        this.dispatchService = dispatchService;
        this.jobRunner = jobRunner;
    }

    @Scheduled(cron = "${fangke.job.grant-cleanup-cron:0 0 3 * * *}")
    public void cleanup() {
        jobRunner.run(JobCodes.GRANT_CLEANUP, this::cleanupOnce);
    }

    /** 清理过期授权（手动执行也走这里）。 */
    public int cleanupOnce() {
        return dispatchService.cleanupExpired();
    }
}
