package com.fangke.visitor.device.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 设备能力位（docs/12 新设备接入指南）。
 *
 * <p>平台只按“语义能力”编排业务，不关心固件怎么实现。换设备时新厂商的协议只要能覆盖
 * 这些能力，业务代码零改动；覆盖不了的能力会被 {@code DeviceCapabilityService} 拦下并
 * 给出明确提示，而不是抛一堆 500。
 */
public enum DeviceCapability {

    PARAM_READ("读取参数"),
    PARAM_WRITE("下发参数"),
    PERSON_UPSERT("新增/更新名单"),
    PERSON_REMOVE("删除名单"),
    PERSON_CLEAR("清空名单"),
    PERSON_LIST("读取名单"),
    VERSION_READ("读取版本"),
    DOOR_OPEN("远程开门"),
    SCREEN_MESSAGE("屏幕提示"),
    TIME_SYNC("时间校准"),
    FACE_PHOTO("人脸照片下发"),
    QR_ONLINE("在线二维码核验"),
    HEARTBEAT("心跳上报"),
    RECORD_CALLBACK("通行记录回调");

    private final String label;

    DeviceCapability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Set<DeviceCapability> all() {
        return EnumSet.allOf(DeviceCapability.class);
    }

    /** 下发名单所必需的最小能力集，用于设备档案自检与交接检查。 */
    public static Set<DeviceCapability> dispatchRequired() {
        return EnumSet.of(PERSON_UPSERT, PERSON_REMOVE, PERSON_LIST);
    }
}
