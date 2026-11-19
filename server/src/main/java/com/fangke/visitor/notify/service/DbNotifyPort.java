package com.fangke.visitor.notify.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.approval.model.ApprovalTask;
import com.fangke.visitor.approval.repository.ApprovalTaskRepository;
import com.fangke.visitor.approval.service.NotifyPort;
import com.fangke.visitor.notify.model.NotifyTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * 审批通知实现：把 M5 的 {@link NotifyPort} 事件翻译成站内消息 + 短信。
 *
 * <p>在此之前 {@code NotifyPort} 是 noop，审批人只能靠刷新页面发现待办；
 * 现在待办、审批结果、催办都会落到 {@code notification} 表，员工端/访客端可直接读。
 */
@Component
public class DbNotifyPort implements NotifyPort {

    private static final Logger log = LoggerFactory.getLogger(DbNotifyPort.class);
    private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final NotifyService notifyService;
    private final AppointmentRepository appointmentRepository;
    private final VisitorRepository visitorRepository;
    private final ApprovalTaskRepository taskRepository;

    public DbNotifyPort(NotifyService notifyService,
                        AppointmentRepository appointmentRepository,
                        VisitorRepository visitorRepository,
                        ApprovalTaskRepository taskRepository) {
        this.notifyService = notifyService;
        this.appointmentRepository = appointmentRepository;
        this.visitorRepository = visitorRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    public void approvalTodo(Long employeeId, Long appointmentId) {
        try {
            Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
            if (appointment == null || employeeId == null) {
                return;
            }
            String visitorName = visitorName(appointment);
            NotifyTemplates.Content content = NotifyTemplates.approvalTodo(visitorName,
                    appointment.getAppointmentNo(), range(appointment), appointment.getPurpose());
            notifyService.notifyEmployee(employeeId, NotifyTemplates.APPROVAL_TODO, content,
                    "APPROVAL", appointment.getAppointmentNo(), null, true);
        } catch (Exception e) {
            log.warn("待办通知失败 employee={} appointment={}：{}", employeeId, appointmentId, e.getMessage());
        }
    }

    @Override
    public void approvalResult(Long appointmentId, boolean passed, String reason) {
        try {
            Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
            if (appointment == null || appointment.getVisitorId() == null) {
                return;
            }
            String visitorName = visitorName(appointment);
            NotifyTemplates.Content content = passed
                    ? NotifyTemplates.approvalPassed(visitorName, range(appointment))
                    : NotifyTemplates.approvalRejected(visitorName, range(appointment), reason);
            notifyService.notifyVisitor(appointment.getVisitorId(),
                    passed ? NotifyTemplates.APPROVAL_PASSED : NotifyTemplates.APPROVAL_REJECTED,
                    content, "APPOINTMENT", appointment.getAppointmentNo(), null, true);
        } catch (Exception e) {
            log.warn("审批结果通知失败 appointment={}：{}", appointmentId, e.getMessage());
        }
    }

    @Override
    public void reminder(Long employeeId, Long taskId) {
        try {
            ApprovalTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                return;
            }
            Appointment appointment = appointmentRepository.findById(task.appointmentId()).orElse(null);
            if (appointment == null) {
                return;
            }
            NotifyTemplates.Content content = NotifyTemplates.approvalRemind(visitorName(appointment),
                    appointment.getAppointmentNo(), range(appointment));
            // 催办会重复发生：用分钟时间片做去重后缀，同一分钟只发一条
            String slice = String.valueOf(System.currentTimeMillis() / 60_000);
            notifyService.notifyEmployee(employeeId, NotifyTemplates.APPROVAL_REMIND, content,
                    "APPROVAL", appointment.getAppointmentNo(), slice, true);
        } catch (Exception e) {
            log.warn("催办通知失败 employee={} task={}：{}", employeeId, taskId, e.getMessage());
        }
    }

    private String visitorName(Appointment appointment) {
        if (appointment.getVisitorId() == null) {
            return null;
        }
        return visitorRepository.findById(appointment.getVisitorId())
                .map(Visitor::getName)
                .orElse(null);
    }

    private String range(Appointment appointment) {
        if (appointment.getStartTime() == null || appointment.getEndTime() == null) {
            return "";
        }
        return RANGE.format(appointment.getStartTime()) + "-"
                + RANGE.format(appointment.getEndTime()).substring(6);
    }
}
