package com.fangke.visitor.job.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.device.service.VisitRecordRepository;
import com.fangke.visitor.job.repository.JobRunLogRepository;
import com.fangke.visitor.notify.model.NotifyTemplates;
import com.fangke.visitor.notify.service.NotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 数据维护类定时任务（M12）：预约过期、在访超时签离、保留期清理。
 *
 * <p>每条都会被 {@link JobRunner} 包一层记 {@code job_run_log}。
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AppointmentRepository appointmentRepository;
    private final VisitRecordRepository visitRecordRepository;
    private final NotifyService notifyService;
    private final JobRunLogRepository jobRunLogRepository;
    private final JdbcTemplate jdbc;

    public MaintenanceService(AppointmentRepository appointmentRepository,
                              VisitRecordRepository visitRecordRepository,
                              NotifyService notifyService,
                              JobRunLogRepository jobRunLogRepository,
                              JdbcTemplate jdbc) {
        this.appointmentRepository = appointmentRepository;
        this.visitRecordRepository = visitRecordRepository;
        this.notifyService = notifyService;
        this.jobRunLogRepository = jobRunLogRepository;
        this.jdbc = jdbc;
    }

    /** 已通过/可通行但已过预约结束时间 → 置「已过期」，并站内通知访客。 */
    @Transactional
    public int expireAppointments(int limit) {
        List<Long> ids = appointmentRepository.findExpirableIds(LocalDateTime.now(), limit);
        int affected = 0;
        for (Long id : ids) {
            appointmentRepository.findById(id).ifPresent(appointment -> {
                appointmentRepository.updateStatus(id, Appointment.EXPIRED);
                notifyExpired(appointment);
            });
            affected++;
        }
        return affected;
    }

    /** 仍在访但预约结束时间已过 → 异常离场（checkoutType=3），预约置已签离。 */
    @Transactional
    public int autoCheckoutOverdueVisits(int limit) {
        List<VisitRecordRepository.OverdueRecord> overdue =
                visitRecordRepository.findOverdueOpen(LocalDateTime.now(), limit);
        int affected = 0;
        for (VisitRecordRepository.OverdueRecord record : overdue) {
            visitRecordRepository.updateCheckout(record.recordId(), LocalDateTime.now(), null,
                    3, 3);
            visitRecordRepository.updateAppointmentStatus(record.appointmentId(),
                    Appointment.CHECKED_OUT);
            affected++;
        }
        if (affected > 0) {
            log.info("自动签离 {} 条在访超时记录", affected);
        }
        return affected;
    }

    /** 设备心跳保留期清理（docs/10 §12，默认 7 天）。 */
    public int purgeHeartbeats(int days) {
        return jdbc.update("DELETE FROM device_heartbeat WHERE created_at < ?",
                LocalDateTime.now().minusDays(Math.max(1, days)));
    }

    /** 通知清理：已读超过 N 天的站内消息 + 超过 M 天的全部消息（默认 30 / 180 天）。 */
    public int purgeNotifications(int readDays, int allDays) {
        LocalDateTime now = LocalDateTime.now();
        int read = jdbc.update("DELETE FROM notification WHERE read_at IS NOT NULL AND read_at < ?",
                now.minusDays(Math.max(1, readDays)));
        int all = jdbc.update("DELETE FROM notification WHERE created_at < ?",
                now.minusDays(Math.max(readDays + 1, allDays)));
        return read + all;
    }

    /** 任务日志保留期清理（默认 30 天）。 */
    public int purgeJobLogs(int days) {
        return jobRunLogRepository.deleteBefore(LocalDateTime.now().minusDays(Math.max(1, days)));
    }

    /** 车闸 MQTT 报文日志保留期清理（默认 30 天）：只记变更与回执，但长期不清理也会堆积。 */
    public int purgeVehicleMqttLogs(int days) {
        return jdbc.update("DELETE FROM vehicle_mqtt_log WHERE created_at < ?",
                LocalDateTime.now().minusDays(Math.max(1, days)));
    }

    private void notifyExpired(Appointment appointment) {
        if (appointment.getVisitorId() == null) {
            return;
        }
        String range = appointment.getStartTime() == null ? ""
                : RANGE.format(appointment.getStartTime()) + "-"
                + RANGE.format(appointment.getEndTime()).substring(6);
        // 过期属于"过期才有意义"的提醒，不重复发短信，避免打扰
        notifyService.notifyVisitor(appointment.getVisitorId(), NotifyTemplates.APPOINTMENT_EXPIRED,
                NotifyTemplates.appointmentExpired(range), "APPOINTMENT",
                appointment.getAppointmentNo(), null, false);
    }
}
