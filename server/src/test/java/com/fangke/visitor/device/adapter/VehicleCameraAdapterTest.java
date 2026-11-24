package com.fangke.visitor.device.adapter;

import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车闸相机占位适配器（docs/18）：能力边界必须"明确拒绝"，不能静默当人行终端用。
 */
class VehicleCameraAdapterTest {

    private final VehicleCameraAdapter adapter = new VehicleCameraAdapter();

    private Device camera() {
        Device d = new Device();
        d.setDeviceSn("CAR-001");
        d.setChannel(Device.Channel.VEHICLE);
        return d;
    }

    @Test
    @DisplayName("只声明车牌识别上报，不声明人员名单能力")
    void capabilitiesArePlateOnly() {
        assertTrue(adapter.supports(Device.Channel.VEHICLE));
        assertFalse(adapter.supports(Device.Channel.PULL));
        assertTrue(adapter.capabilities().contains(DeviceCapability.PLATE_CALLBACK));
        assertFalse(adapter.capabilities().contains(DeviceCapability.PERSON_UPSERT));
        assertFalse(adapter.capabilities().contains(DeviceCapability.PLATE_UPSERT),
                "厂商协议未接入前不能声称能下发车牌名单");
    }

    @Test
    @DisplayName("人员名单/开闸等动作明确报不支持，并提示去要厂商文档")
    void personActionsAreRejected() {
        DeviceException e = assertThrows(DeviceException.class,
                () -> adapter.upsertPerson(camera(), null));
        assertTrue(e.getMessage().contains("车闸相机"));
        assertThrows(DeviceException.class, () -> adapter.openDoor(camera()));
        assertThrows(DeviceException.class, () -> adapter.pushPlates(camera(), java.util.List.of()));
    }
}
