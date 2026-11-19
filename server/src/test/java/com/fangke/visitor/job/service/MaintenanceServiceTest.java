package com.fangke.visitor.job.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.device.service.VisitRecordRepository;
import com.fangke.visitor.job.repository.JobRunLog;
import com.fangke.visitor.job.repository.JobRunLogRepository;
import com.fangke.visitor.notify.model.NotifyTemplates;
import com.fangke.visitor.notify.service.NotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据维护任务：预约过期、在访超时签离、以及任务日志包装。
 */
class MaintenanceServiceTest {

    private AppointmentRepository appointmentRepo;
    private VisitRecordRepository visitRepo;
    private NotifyService notifyService;
    private JobRunLogRepository jobLogRepo;
    private MaintenanceService service;

    @BeforeEach
    void setUp() {
        appointmentRepo = mock(AppointmentRepository.class);
        visitRepo = mock(VisitRecordRepository.class);
        notifyService = mock(NotifyService.class);
        jobLogRepo = mock(JobRunLogRepository.class);
        service = new MaintenanceService(appointmentRepo, visitRepo, notifyService,
                jobLogRepo, mock(JdbcTemplate.class));
    }

    @Test
    @DisplayName("预约过期：置为已过期并站内通知访客")
    void expireAppointments() {
        Appointment appointment = new Appointment();
        appointment.setId(3L);
        appointment.setAppointmentNo("A2026111600001");
        appointment.setVisitorId(9L);
        appointment.setStatus(Appointment.GRANTED);
        appointment.setStartTime(LocalDateTime.of(2026, 11, 16, 9, 0));
        appointment.setEndTime(LocalDateTime.of(2026, 11, 16, 18, 0));

        when(appointmentRepo.findExpirableIds(any(), eq(10))).thenReturn(List.of(3L));
        when(appointmentRepo.findById(3L)).thenReturn(Optional.of(appointment));

        int affected = service.expireAppointments(10);

        assertEquals(1, affected);
        verify(appointmentRepo).updateStatus(3L, Appointment.EXPIRED);
        verify(notifyService).notifyVisitor(eq(9L), eq(NotifyTemplates.APPOINTMENT_EXPIRED),
                any(NotifyTemplates.Content.class), eq("APPOINTMENT"), eq("A2026111600001"),
                eq(null), eq(false));
    }

    @Test
    @DisplayName("在访超时：异常离场 + 预约置已签离")
    void autoCheckoutOverdue() {
        when(visitRepo.findOverdueOpen(any(), eq(10)))
                .thenReturn(List.of(new VisitRecordRepository.OverdueRecord(100L, 12L)));

        int affected = service.autoCheckoutOverdueVisits(10);

        assertEquals(1, affected);
        verify(visitRepo).updateCheckout(eq(100L), any(), eq(null), eq(3), eq(3));
        verify(visitRepo).updateAppointmentStatus(12L, Appointment.CHECKED_OUT);
    }

    @Test
    @DisplayName("没有到期数据时不写库、不发通知")
    void nothingToDo() {
        when(appointmentRepo.findExpirableIds(any(), anyInt())).thenReturn(List.of());
        when(visitRepo.findOverdueOpen(any(), anyInt())).thenReturn(List.of());

        assertEquals(0, service.expireAppointments(10));
        assertEquals(0, service.autoCheckoutOverdueVisits(10));
        verify(appointmentRepo, never()).updateStatus(anyLong(), anyInt());
        verify(notifyService, never()).notifyVisitor(anyLong(), any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("JobRunner：成功记处理条数，失败记失败且不抛异常")
    void jobRunnerWrapsResult() {
        when(jobLogRepo.start(any(), any())).thenReturn(1L, 2L);
        JobRunner runner = new JobRunner(jobLogRepo);

        int ok = runner.run(JobCodes.APPOINTMENT_EXPIRE, () -> 7);
        int failed = runner.run(JobCodes.VISIT_AUTO_CHECKOUT, () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(7, ok);
        assertEquals(0, failed);
        verify(jobLogRepo).finish(eq(1L), eq(JobRunLog.STATUS_SUCCESS), eq(7), eq(null), any());
        verify(jobLogRepo).finish(eq(2L), eq(JobRunLog.STATUS_FAILED), eq(0), eq("boom"), any());
    }
}
