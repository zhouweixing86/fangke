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

    /** 车辆通行（C-68）：某车牌在指定日期已授权（可通行/已签到）的预约单。 */
    /**
     * 车辆通行（C-68）：某车牌在指定日期已授权（可通行/已签到）的预约单。
     *
     * <p>默认返回空，方便测试替身只实现自己关心的方法；生产实现见 JdbcAppointmentRepository。
     */
    default List<Appointment> findByPlateNoOnDate(String plateNo, java.time.LocalDate date) {
        return List.of();
    }

    /**
     * 到期未到访的预约（status ∈ {30 已通过, 35 可通行} 且 end_time 早于 before）。
     * 定时任务用：置为「已过期」并通知访客。
     */
    List<Long> findExpirableIds(java.time.LocalDateTime before, int limit);
}
