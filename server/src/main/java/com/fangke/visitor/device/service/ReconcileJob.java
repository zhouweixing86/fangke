package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.job.service.JobCodes;
import com.fangke.visitor.job.service.JobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每日对账（docs/02 JOB-04，默认 02:00）。
 *
 * <p>与设备上线触发的对账共用 {@link ReconcileService}，单台失败不影响其它设备；
 * 执行结果统一记入 {@code job_run_log}，可在管理端「运维中心」查看。
 */
@Component
public class ReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ReconcileJob.class);

    private final DeviceRepository deviceRepository;
    private final ReconcileService reconcileService;
    private final JobRunner jobRunner;

    public ReconcileJob(DeviceRepository deviceRepository, ReconcileService reconcileService,
                        JobRunner jobRunner) {
        this.deviceRepository = deviceRepository;
        this.reconcileService = reconcileService;
        this.jobRunner = jobRunner;
    }

    @Scheduled(cron = "${fangke.job.reconcile-cron:0 0 2 * * *}")
    public void dailyReconcile() {
        jobRunner.run(JobCodes.RECONCILE, this::reconcileAll);
    }

    /** 对账全部启用设备，返回成功台数（手动执行也走这里）。 */
    public int reconcileAll() {
        List<Device> devices = deviceRepository.findAllActive();
        log.info("每日对账开始，设备数={}", devices.size());
        int ok = 0;
        for (Device device : devices) {
            if (device.getId() == null) {
                continue;
            }
            try {
                ReconcileService.Result result = reconcileService.reconcile(device.getId());
                log.info("对账完成 device={} expected={} actual={} missing={} extra={} removed={} failedRemove={}",
                        result.deviceSn(), result.expectedCount(), result.actualCount(),
                        result.missingInDevice().size(), result.extraInDevice().size(),
                        result.removed(), result.failedRemoval());
                ok++;
            } catch (Exception e) {
                log.warn("对账失败 device={}：{}", device.getDeviceSn(), e.getMessage());
            }
        }
        log.info("每日对账结束，成功={} 失败={}", ok, devices.size() - ok);
        return ok;
    }
}
