package com.fangke.visitor.vehicle.model;

import java.time.LocalDateTime;

/**
 * 车辆通行事件（docs/10 vehicle_event）。
 *
 * @param direction    0 出 / 1 进，取设备上报
 * @param matchedType  1 员工 / 2 访客 / null 未匹配
 * @param personNo     人员编号（员工 E000123 / 访客 V202611190001），用于和行人记录按人合并
 * @param resultStatus 1 放行 / 0 拒绝
 */
public record VehicleEvent(
        Long id,
        String eventUuid,
        String deviceSn,
        Long gateId,
        String plateNo,
        int direction,
        LocalDateTime eventTime,
        Integer confidence,
        Integer matchedType,
        Long matchedId,
        String matchedName,
        String personNo,
        int resultStatus,
        String failReason,
        String photoKey,
        String photoUrl,
        String rawJson) {

    public static final int TYPE_EMPLOYEE = 1;
    public static final int TYPE_VISITOR = 2;

    public static final int RESULT_PASS = 1;
    public static final int RESULT_DENY = 0;

    public static String typeText(Integer type) {
        if (type == null) {
            return "未匹配";
        }
        return switch (type) {
            case TYPE_EMPLOYEE -> "员工";
            case TYPE_VISITOR -> "访客";
            default -> "未知";
        };
    }
}
