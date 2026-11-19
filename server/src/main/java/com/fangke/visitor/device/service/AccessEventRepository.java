package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.AccessEvent;

import java.util.Optional;

/**
 * 通行事件仓储。{@link #insertIfAbsent} 必须保证幂等：
 * 设备没收到 {@code {"message":"ok","result":0}} 时会无限重推同一条。
 */
public interface AccessEventRepository {

    /**
     * @return 首次写入返回事件主键（用于关联照片）；重复事件返回 {@link Optional#empty()}
     */
    Optional<Long> insertIfAbsent(AccessEvent event);
}
