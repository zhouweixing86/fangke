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

    /** 按通道取启用设备：车闸相机单独管理时用（docs/18）。 */
    List<Device> findActiveByChannel(Device.Channel channel);

    /**
     * 一个门点可能同时挂人行闸与车闸；人员/黑名单下发只能发给人行终端。
     *
     * <p>写成静态方法而不是仓储默认方法：测试里 deviceRepository 是 mock，
     * 默认方法会被 mock 成空返回，业务就"静默不下发"了。
     */
    static List<Device> personDevices(List<Device> devices) {
        if (devices == null) {
            return List.of();
        }
        return devices.stream().filter(DeviceRepository::isPersonDevice).toList();
    }

    static boolean isPersonDevice(Device device) {
        return device != null && device.getChannel() != Device.Channel.VEHICLE;
    }

    /**
     * 心跳落库：更新在线状态与容量，并按策略写入 device_heartbeat 样本。
     *
     * @param clockSkewSeconds 设备时间与服务器时间的差值（绝对值），可为 null
     */
    void updateHeartbeat(String deviceSn, String ip, Integer personCount,
                         LocalDateTime heartbeatAt, Integer clockSkewSeconds, String rawJson);

    /**
     * 只刷新在线状态与内网 IP，不写 device_heartbeat 样本。
     *
     * <p>给 MQTT 车闸相机用：它 5 秒一条心跳，按样本写库会撑爆表。
     */
    default void touchHeartbeat(String deviceSn, String ip, LocalDateTime at) {
        updateHeartbeat(deviceSn, ip, null, at, null, null);
    }

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
                    Long gateId, String remark, String ip);

    /** 改通道（人行终端 ↔ 车闸相机）：单独管理时用来纠正误登记的类型。 */
    void updateChannel(Long deviceId, Device.Channel channel);

    /**
     * 保存「平台期望的设备参数」（白名单键的 0/1 值，JSON）。
     *
     * <p>轮询式设备不能被直连，改参数只能靠心跳来拉 {@code syncDeviceParameter} 时下发，
     * 所以这里必须持久化，否则队列里只有一条「去拉参数」的指令、内容却是空的。
     */
    default void updateParamOverrides(Long deviceId, String json) {
    }

    /** 门点下的设备数量（门点停用前要先清空，避免设备无门可投递）。 */
    int countByGate(Long gateId);

    /**
     * 删除设备档案，连同它的指令队列与人员状态。
     *
     * <p>只给「登记错了 / 换下来的旧机器」用；在线设备请先断电或断网，避免删掉正在跑的设备。
     */
    void deleteById(Long deviceId);
}
