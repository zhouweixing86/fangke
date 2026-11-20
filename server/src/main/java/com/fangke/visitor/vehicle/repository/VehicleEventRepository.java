package com.fangke.visitor.vehicle.repository;

import com.fangke.visitor.vehicle.model.VehicleEvent;

import java.time.LocalDate;
import java.util.List;

public interface VehicleEventRepository {

    /** 幂等写入（同一 event_uuid 只落一条）；返回 true 表示新写入。 */
    boolean insertIfAbsent(VehicleEvent event);

    List<VehicleEvent> search(LocalDate from, LocalDate to, String plateNo, Long gateId,
                              Integer resultStatus, int offset, int limit);

    long count(LocalDate from, LocalDate to, String plateNo, Long gateId, Integer resultStatus);
}
