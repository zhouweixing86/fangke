package com.fangke.visitor.vehicle.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 车辆事件落库前的按列截断：一条脏数据不能把整条通行记录顶掉（STRICT 模式会直接报错）。 */
class JdbcVehicleEventRepositoryTest {

    @Test
    @DisplayName("按列宽截断：空值保持 null，超长按上限截断")
    void cutFitsColumn() {
        assertNull(JdbcVehicleEventRepository.cut(null, 16));
        assertEquals("苏A12345", JdbcVehicleEventRepository.cut("苏A12345", 16));
        assertEquals(16, JdbcVehicleEventRepository.cut("苏A123459999999999999", 16).length());
        assertEquals("苏A12345", JdbcVehicleEventRepository.cut("苏A12345", 32));
    }
}
