package com.fangke.visitor.visit.model;

import java.time.LocalDateTime;

/**
 * 来访登记（V26）：管理端现场录入的访客到访记录（不是预约）。
 *
 * <p>字段对齐参考站首页列表：区域、事由、手机号、姓名、车牌号、来访单位、人数、到达时间、是否离开、离开时间。
 */
public record VisitRegister(Long id, Long areaId, String areaName, Long causeId, String causeName,
                            String visitorName, String phoneMask, String plateNo, String company,
                            int personCount, LocalDateTime arrivalTime, LocalDateTime leaveTime,
                            int status, String remark, LocalDateTime createdAt) {

    public static final int STATUS_INSIDE = 1;
    public static final int STATUS_LEFT = 2;

    public boolean inside() {
        return status == STATUS_INSIDE;
    }
}
