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

    /**
     * 找同一台设备、前后 60 秒内、由<b>平台核验</b>写的二维码事件。
     *
     * <p>设备扫二维码的流程会产生两条记录：设备把码推给平台（平台写一条），设备自己再回传一条。
     * 用这个方法把后者合并到前者，避免一次扫码在通行记录里出现两条。
     *
     * <p>优先按设备 SN 匹配（平台核验时记的就是真实 SN）；保安台核验那种没有设备的情况
     * （{@code GUARD-QR}）也允许合并。
     */
    Optional<Long> findPlatformQrEvent(String deviceSn, Long gateId, java.time.LocalDateTime eventTime);
}
