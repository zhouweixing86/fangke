package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.AccessEvent;

/**
 * 通行事件 → 到访记录的后续处理（签到/签离）。
 *
 * <p>抽成接口是为了让设备记录入库逻辑的单测不依赖数据库。
 */
public interface VisitRecorder {

    /**
     * @param event   已入库的通行事件
     * @param eventId 事件主键（用于回写 checkin_event / checkout_event）
     */
    void record(AccessEvent event, Long eventId);

    static VisitRecorder noop() {
        return (event, eventId) -> {
        };
    }
}
