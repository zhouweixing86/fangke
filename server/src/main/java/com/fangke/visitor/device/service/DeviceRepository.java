package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.Device;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 设备档案仓储。抽成接口是为了让业务服务可以脱离数据库做单测
 * （测试用 InMemoryDeviceRepository，生产用 JdbcDeviceRepository）。
 */
public interface DeviceRepository {

    Optional<Device> findBySn(String deviceSn);

    Optional<Device> findById(Long id);

    /** 部分固件只上报 MAC（dev_sno），需要按 MAC 反查。 */
    Optional<Device> findByMac(String mac);

    List<Device> findActiveByGate(Long gateId);

    /** 全部启用设备（对账定时任务用）。 */
    List<Device> findAllActive();

    /**
     * 心跳落库：更新在线状态与容量，并按策略写入 device_heartbeat 样本。
     *
     * @param clockSkewSeconds 设备时间与服务器时间的差值（绝对值），可为 null
     */
    void updateHeartbeat(String deviceSn, String ip, Integer personCount,
                         LocalDateTime heartbeatAt, Integer clockSkewSeconds, String rawJson);

    void updateFirmware(String deviceSn, String firmware, String model);

    /**
     * 只更新设备上的人数（名单变动回执后回填）。
     *
     * <p>默认空实现，方便测试替身只实现自己关心的方法。
     */
    default void updatePersonCount(Long deviceId, int personCount) {
    }

    /**
     * 自动登记新设备（轮询式终端首次心跳时调用，避免现场还要手工建档）。
     *
     * @return 新设备主键
     */
    long register(Device device);

    /** 自动登记时 mac 是占位值，设备上报真实 mac 后补齐。 */
    void bindMac(Long deviceId, String mac);

    /**
     * 现场补档案：设备名、型号、出入口方向、所属门点、备注。
     * 轮询式终端自动登记后只有 SN/IP，需要在后台分配到真实门点。
     */
    void updateMeta(Long deviceId, String deviceName, String model, Integer direction,
                    Long gateId, String remark);

    /** 门点下的设备数量（门点停用前要先清空，避免设备无门可投递）。 */
    int countByGate(Long gateId);

    /**
     * 删除设备档案，连同它的指令队列与人员状态。
     *
     * <p>只给「登记错了 / 换下来的旧机器」用；在线设备请先断电或断网，避免删掉正在跑的设备。
     */
    void deleteById(Long deviceId);
}
