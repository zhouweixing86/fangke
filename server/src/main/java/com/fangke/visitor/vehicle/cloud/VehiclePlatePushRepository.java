package com.fangke.visitor.vehicle.cloud;

import java.util.List;

/** 车闸车牌下发快照仓储（测试用内存实现，生产用 JDBC）。 */
public interface VehiclePlatePushRepository {

    List<VehiclePlatePush> findAll();

    /** 新增或覆盖（同一车牌只保留一条）。 */
    void save(VehiclePlatePush row);

    void delete(String plateNo);

    void markFailed(String plateNo, String error);
}
