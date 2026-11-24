package com.fangke.visitor.device.model;

import java.time.LocalDateTime;

/**
 * 下发给车闸相机的车牌条目（docs/18）。
 *
 * <p>车牌来源只有两处，和行人名单同源：
 * <ul>
 *   <li>员工白名单：{@code employee.plate_no}（长期有效）</li>
 *   <li>访客预约：{@code appointment.plate_no}（当天 + 批准门点）</li>
 * </ul>
 * 车闸相机厂商协议未定，这里只固化平台侧的「语义载荷」；具体字段名由适配器映射。
 */
public record PlatePayload(String plateNo, String ownerType, Long ownerId, String ownerName,
                           String personNo, LocalDateTime validFrom, LocalDateTime validTo) {

    public static final String OWNER_EMPLOYEE = "EMPLOYEE";
    public static final String OWNER_VISITOR = "VISITOR";
}
