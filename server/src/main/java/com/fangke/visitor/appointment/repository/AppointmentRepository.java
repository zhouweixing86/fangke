package com.fangke.visitor.appointment.repository;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;

import java.util.List;
import java.util.Optional;

public interface AppointmentRepository {

    long insert(Appointment appointment);

    void update(Appointment appointment);

    void updateStatus(Long id, int status);

    Optional<Appointment> findByNo(String appointmentNo);

    Optional<Appointment> findById(Long id);

    List<Appointment> findByVisitor(Long visitorId, Integer status, int offset, int limit);

    long countByVisitor(Long visitorId, Integer status);

    /** 同一访客是否已有进行中的预约（BR-P 系列：不允许重复提交）。 */
    Optional<Appointment> findInProgress(Long visitorId);

    /**
     * 某访客当前仍"有效"的预约（待审批/待二级/已通过/可通行/已签到）。
     * 拉黑立即生效时用来批量撤销。
     */
    List<Appointment> findActiveByVisitor(Long visitorId);

    void insertMate(AppointmentMate mate);

    List<AppointmentMate> findMates(Long appointmentId);

    /** 当日最大单号（用于生成 A+yyyyMMdd+5位序号）。 */
    Optional<String> findMaxNoByPrefix(String prefix);

    /**
     * 到期未到访的预约（status ∈ {30 已通过, 35 可通行} 且 end_time 早于 before）。
     * 定时任务用：置为「已过期」并通知访客。
     */
    List<Long> findExpirableIds(java.time.LocalDateTime before, int limit);
}
