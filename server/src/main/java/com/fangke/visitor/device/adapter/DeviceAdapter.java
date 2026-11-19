package com.fangke.visitor.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备适配层统一语义接口（docs/09 §2.1）。
 *
 * <p>业务代码只依赖本接口，不直接调用 8091 或 MQTT。
 * 局域网实现：{@link LanHttpAdapter}；云实现：{@link CloudMqttAdapter}（P5 补齐）。
 */
public interface DeviceAdapter {

    /** 是否支持该设备通道。 */
    boolean supports(Device.Channel channel);

    /**
     * 该通道**协议层**原生支持的能力集（docs/12）。
     *
     * <p>换设备时新适配器只需如实声明，业务侧不支持的指令会被提前拦下；
     * 单台设备的差异（例如某台机器没接读卡器）再通过 {@code device.capability} 覆盖。
     */
    default Set<DeviceCapability> capabilities() {
        return DeviceCapability.all();
    }

    /** 读取设备全量参数（getDeviceParameter.data）。 */
    JsonNode readParams(Device device);

    /**
     * 部分参数下发。
     *
     * <p>实现必须“取全量 → 合并 → 整体回写”，避免个别固件把未传字段重置为默认值
     * （docs/09 §1.1 开发要点 3）。
     */
    void writeParams(Device device, Map<String, Object> partial);

    /** 新增或覆盖名单（访客无照片 / 员工带照片走同一接口）。 */
    void upsertPerson(Device device, PersonPayload payload);

    /** 删除名单。 */
    void removePerson(Device device, String personNo, String userType);

    /** 清空全部名单（换机/重置用，慎用）。 */
    void clearAllPersons(Device device);

    /** 读取设备当前名单的人员编号列表。 */
    List<String> listPersonNos(Device device);

    /** 读取固件版本信息。 */
    JsonNode readVersion(Device device);

    /** 远程开门。 */
    void openDoor(Device device);

    /** 屏幕提示（≤14 个汉字，显示 3 秒）。 */
    void showMessage(Device device, String message);

    /** 校准设备时间。 */
    void setDeviceTime(Device device, LocalDateTime time);
}
