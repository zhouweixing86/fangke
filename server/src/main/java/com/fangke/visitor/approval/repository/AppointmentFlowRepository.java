package com.fangke.visitor.approval.repository;

import com.fangke.visitor.approval.model.FlowNode;

import java.util.List;
import java.util.Optional;

/** 预约审批流程快照仓储（V31 / C-82）。 */
public interface AppointmentFlowRepository {

    void saveAll(Long appointmentId, List<FlowNode> nodes);

    List<FlowNode> findByAppointment(Long appointmentId);

    Optional<FlowNode> find(Long appointmentId, int seq);

    /** 下一个节点（seq 大于当前）。 */
    Optional<FlowNode> next(Long appointmentId, int seq);
}
