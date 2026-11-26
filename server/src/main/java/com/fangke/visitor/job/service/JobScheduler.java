package com.fangke.visitor.job.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.dispatch.service.DispatchCleanupJob;
import com.fangke.visitor.notify.service.NotifyService;
import com.fangke.visitor.qr.service.QrTicketService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * M12 定时任务调度（cron 全部可在 application.yml / 环境变量覆盖）。
 *
 * <p>已有任务（对账、授权过期清理、二维码票据清理）在各自模块里，这里补齐
 * 审批催办、预约过期、在访超时签离、短信重试与保留期清理。
 * 每个任务都能通过 {@link #runOnce(String)} 手动触发（运维兜底 + 验收用）。
 */
@Component
public class JobScheduler {

    private final JobRunner runner;
    private final ApprovalReminderService reminderService;
    private final MaintenanceService maintenanceService;
    private final NotifyService notifyService;
    private final QrTicketService qrTicketService;
    private final DispatchCleanupJob dispatchCleanupJob;

    @Value("${fangke.job.approval-remind-minutes:120}")
    private int approvalRemindMinutes;

    @Value("${fangke.job.approval-remind-batch:100}")
    private int approvalRemindBatch;

    @Value("${fangke.job.heartbeat-retention-days:7}")
    private int heartbeatRetentionDays;

    @Value("${fangke.job.notification-read-retention-days:30}")
    private int notificationReadDays;

    @Value("${fangke.job.notification-retention-days:180}")
    private int notificationAllDays;

    @Value("${fangke.job.job-log-retention-days:15}")
    private int jobLogDays;

    @Value("${fangke.job.vehicle-mqtt-log-retention-days:15}")
    private int vehicleMqttLogDays;

    @Value("${fangke.job.batch-size:200}")
    private int batchSize;

    public JobScheduler(JobRunner runner, ApprovalReminderService reminderService,
                        MaintenanceService maintenanceService, NotifyService notifyService,
                        QrTicketService qrTicketService,
                        DispatchCleanupJob dispatchCleanupJob) {
        this.runner = runner;
        this.reminderService = reminderService;
        this.maintenanceService = maintenanceService;
        this.notifyService = notifyService;
        this.qrTicketService = qrTicketService;
        this.dispatchCleanupJob = dispatchCleanupJob;
    }

    /** 手动执行一次（管理端「运维中心 → 立即执行」）。 */
    public int runOnce(String jobCode) {
        if (jobCode == null || jobCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "任务编码不能为空");
        }
        return switch (jobCode.trim().toUpperCase()) {
            case JobCodes.APPROVAL_REMIND -> runner.run(JobCodes.APPROVAL_REMIND,
                    () -> reminderService.remindStale(approvalRemindMinutes, 30, approvalRemindBatch));
            case JobCodes.APPOINTMENT_EXPIRE -> runner.run(JobCodes.APPOINTMENT_EXPIRE,
                    () -> maintenanceService.expireAppointments(batchSize));
            case JobCodes.VISIT_AUTO_CHECKOUT -> runner.run(JobCodes.VISIT_AUTO_CHECKOUT,
                    () -> maintenanceService.autoCheckoutOverdueVisits(batchSize));
            case JobCodes.NOTIFICATION_RETRY -> runner.run(JobCodes.NOTIFICATION_RETRY,
                    () -> notifyService.retryFailed(50));
            case JobCodes.DATA_RETENTION -> runner.run(JobCodes.DATA_RETENTION, this::purgeAll);
            case JobCodes.GRANT_CLEANUP -> runner.run(JobCodes.GRANT_CLEANUP, dispatchCleanupJob::cleanupOnce);
            case JobCodes.QR_CLEANUP -> runner.run(JobCodes.QR_CLEANUP, qrTicketService::housekeeping);
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "未知任务：" + jobCode);
        };
    }

    @Scheduled(cron = "${fangke.job.approval-remind-cron:0 */10 * * * *}")
    public void approvalRemind() {
        runOnce(JobCodes.APPROVAL_REMIND);
    }

    @Scheduled(cron = "${fangke.job.appointment-expire-cron:0 5 * * * *}")
    public void appointmentExpire() {
        runOnce(JobCodes.APPOINTMENT_EXPIRE);
    }

    @Scheduled(cron = "${fangke.job.visit-checkout-cron:0 15 * * * *}")
    public void visitAutoCheckout() {
        runOnce(JobCodes.VISIT_AUTO_CHECKOUT);
    }

    @Scheduled(fixedDelayString = "${fangke.job.notification-retry-ms:60000}",
            initialDelayString = "${fangke.job.notification-retry-initial-ms:60000}")
    public void notificationRetry() {
        runOnce(JobCodes.NOTIFICATION_RETRY);
    }

    @Scheduled(cron = "${fangke.job.retention-cron:0 40 4 * * *}")
    public void dataRetention() {
        runOnce(JobCodes.DATA_RETENTION);
    }

    /** 二维码票据清理：默认每天 03:20。 */
    @Scheduled(cron = "${fangke.job.qr-cleanup-cron:0 20 3 * * *}")
    public void qrCleanup() {
        runOnce(JobCodes.QR_CLEANUP);
    }

    private int purgeAll() {
        int heartbeats = maintenanceService.purgeHeartbeats(heartbeatRetentionDays);
        int notifications = maintenanceService.purgeNotifications(notificationReadDays, notificationAllDays);
        int jobLogs = maintenanceService.purgeJobLogs(jobLogDays);
        int vehicleLogs = maintenanceService.purgeVehicleMqttLogs(vehicleMqttLogDays);
        return heartbeats + notifications + jobLogs + vehicleLogs;
    }
}
