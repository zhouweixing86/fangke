package com.fangke.visitor.appointment.repository;

import java.util.Collection;
import java.util.List;

/**
 * 预约门点集合（C-64）：一张预约单可以申请/批准多个门点。
 *
 * <p>两级集合分开存，是为了留下"申请 vs 批准"的差异证据（审批人可增可减）。
 */
public interface AppointmentGateRepository {

    /** 访客申请的门点。 */
    int STAGE_APPLIED = 1;
    /** 审批人批准的门点（最终授权集合）。 */
    int STAGE_APPROVED = 2;

    List<Long> findGateIds(Long appointmentId, int stage);

    /** 覆盖式写入：先清空该 stage 再写入，保证不会残留上一次的增删结果。 */
    void replaceStage(Long appointmentId, int stage, Collection<Long> gateIds);

    void clearStage(Long appointmentId, int stage);
}
