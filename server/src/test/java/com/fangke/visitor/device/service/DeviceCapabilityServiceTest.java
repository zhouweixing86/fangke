package com.fangke.visitor.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备能力裁决：适配器声明 ∩ 设备档案覆盖（docs/12）。
 */
class DeviceCapabilityServiceTest {

    private final DeviceCapabilityService service = new DeviceCapabilityService(
            new DeviceAdapterFactory(List.of(new StubAdapter())));

    private Device device(String capabilityJson) {
        Device d = new Device();
        d.setId(1L);
        d.setDeviceSn("NEW-DEVICE-001");
        d.setModel("YC-P208-NEW");
        d.setChannel(Device.Channel.LAN);
        d.setCapabilityJson(capabilityJson);
        return d;
    }

    @Test
    @DisplayName("无覆盖配置时等于适配器声明")
    void followsAdapterByDefault() {
        assertEquals(StubAdapter.CAPS, service.effective(device(null)));
    }

    @Test
    @DisplayName("disable 屏蔽单台设备不支持的能力")
    void disableRemovesCapability() {
        Set<DeviceCapability> caps = service.effective(device("{\"disable\":[\"SCREEN_MESSAGE\"]}"));
        assertFalse(caps.contains(DeviceCapability.SCREEN_MESSAGE));
        assertTrue(caps.contains(DeviceCapability.PARAM_READ));
    }

    @Test
    @DisplayName("only 只保留白名单，且不会超出适配器能力")
    void onlyRestrictsList() {
        Set<DeviceCapability> caps = service.effective(device(
                "{\"only\":[\"PERSON_UPSERT\",\"PERSON_LIST\",\"FACE_PHOTO\"]}"));
        assertEquals(EnumSet.of(DeviceCapability.PERSON_UPSERT, DeviceCapability.PERSON_LIST), caps);
    }

    @Test
    @DisplayName("裸数组等价于 only")
    void bareArrayIsOnly() {
        Set<DeviceCapability> caps = service.effective(device("[\"PARAM_READ\"]"));
        assertEquals(EnumSet.of(DeviceCapability.PARAM_READ), caps);
    }

    @Test
    @DisplayName("非法 JSON / 未知能力位不影响主流程")
    void invalidOverrideIsTolerated() {
        assertEquals(StubAdapter.CAPS, service.effective(device("{not-json")));
        Set<DeviceCapability> caps = service.effective(device("{\"disable\":[\"NOT_A_CAPABILITY\"]}"));
        assertEquals(StubAdapter.CAPS, caps);
    }

    @Test
    @DisplayName("不支持的能力抛 4006，提示里带型号与中文能力名")
    void requireThrowsWithReadableMessage() {
        Device d = device("{\"disable\":[\"SCREEN_MESSAGE\"]}");
        DeviceException e = assertThrows(DeviceException.class,
                () -> service.require(d, DeviceCapability.SCREEN_MESSAGE, "屏幕提示"));
        assertEquals(ErrorCode.DEVICE_UNSUPPORTED.code(), e.getCode());
        assertTrue(e.getMessage().contains("YC-P208-NEW"));
        assertTrue(e.getMessage().contains("屏幕提示"));
    }

    @Test
    @DisplayName("名单下发关键能力缺失时能被自检发现")
    void missingDispatchCapabilities() {
        Device ok = device(null);
        assertTrue(service.missingDispatchCapabilities(ok).isEmpty());

        Device limited = device("{\"only\":[\"PARAM_READ\"]}");
        assertEquals(DeviceCapability.dispatchRequired(), service.missingDispatchCapabilities(limited));
    }

    @Test
    @DisplayName("能力矩阵逐项给出 supported / byAdapter")
    void describeReportsBothLayers() {
        List<Map<String, Object>> rows = service.describe(device("{\"disable\":[\"TIME_SYNC\"]}"));
        assertEquals(DeviceCapability.values().length, rows.size());
        Map<String, Object> timeSync = rows.stream()
                .filter(r -> "TIME_SYNC".equals(r.get("name"))).findFirst().orElseThrow();
        assertEquals(false, timeSync.get("supported"));
        assertEquals(true, timeSync.get("byAdapter"));

        Map<String, Object> face = rows.stream()
                .filter(r -> "FACE_PHOTO".equals(r.get("name"))).findFirst().orElseThrow();
        assertEquals(false, face.get("supported"));
        assertEquals(false, face.get("byAdapter"));
    }

    @Test
    @DisplayName("通道无适配器时按零能力处理，不抛异常")
    void unknownChannelMeansNoCapability() {
        Device cloud = device(null);
        cloud.setChannel(Device.Channel.CLOUD);
        assertTrue(service.effective(cloud).isEmpty());
    }

    /** 模拟“新设备适配器”：声明列表由测试控制。 */
    static class StubAdapter implements DeviceAdapter {

        static final Set<DeviceCapability> CAPS = EnumSet.of(
                DeviceCapability.PARAM_READ,
                DeviceCapability.PARAM_WRITE,
                DeviceCapability.PERSON_UPSERT,
                DeviceCapability.PERSON_REMOVE,
                DeviceCapability.PERSON_LIST,
                DeviceCapability.DOOR_OPEN,
                DeviceCapability.SCREEN_MESSAGE,
                DeviceCapability.TIME_SYNC);

        @Override
        public boolean supports(Device.Channel channel) {
            return channel == Device.Channel.LAN;
        }

        @Override
        public Set<DeviceCapability> capabilities() {
            return CAPS;
        }

        @Override
        public JsonNode readParams(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeParams(Device device, Map<String, Object> partial) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void upsertPerson(Device device, PersonPayload payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removePerson(Device device, String personNo, String userType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearAllPersons(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> listPersonNos(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode readVersion(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void openDoor(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showMessage(Device device, String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setDeviceTime(Device device, LocalDateTime time) {
            throw new UnsupportedOperationException();
        }
    }
}
