package com.fangke.visitor.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.gateway.DeviceGateway;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备接入自检（docs/12 §3）：换设备后一次点击看到能不能用。
 */
class DeviceSelfCheckServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private DeviceSelfCheckService build(DeviceAdapter adapter) {
        AppProperties props = new AppProperties();
        props.getDevice().setMaxRetry(1);
        DeviceAdapterFactory factory = new DeviceAdapterFactory(List.of(adapter));
        return new DeviceSelfCheckService(factory, new DeviceGateway(props),
                new DeviceCapabilityService(factory));
    }

    private Device device() {
        Device d = new Device();
        d.setId(9L);
        d.setDeviceSn("NEW-DEVICE-001");
        d.setModel("YC-P208-NEW");
        d.setFirmware("V1.0.0");
        d.setChannel(Device.Channel.LAN);
        d.setCapacity(500);
        d.setIp("192.168.10.200");
        return d;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> item(Map<String, Object> result, String key) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        return items.stream().filter(i -> key.equals(i.get("key"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("协议齐全的新设备：自检全绿")
    void healthyDevice() {
        Map<String, Object> result = build(new HealthyAdapter()).check(device());

        assertEquals("OK", result.get("status"));
        assertEquals("OK", item(result, "capability").get("status"));
        assertEquals("OK", item(result, "version").get("status"));
        assertEquals("OK", item(result, "params").get("status"));
        assertEquals("OK", item(result, "persons").get("status"));
        assertTrue(String.valueOf(item(result, "version").get("detail")).contains("V9.9.9"));
    }

    @Test
    @DisplayName("参数缺字段：WARN 并点名缺哪些字段")
    void missingParamsWarns() {
        Map<String, Object> result = build(new HealthyAdapter() {
            @Override
            public JsonNode readParams(Device device) {
                ObjectNode node = mapper.createObjectNode();
                node.put("platformEnable", 0);
                return node;
            }
        }).check(device());

        assertEquals("WARN", result.get("status"));
        Map<String, Object> params = item(result, "params");
        assertEquals("WARN", params.get("status"));
        assertTrue(String.valueOf(params.get("detail")).contains("heartBeatEnable"));
    }

    @Test
    @DisplayName("名单能力缺失：能力项 FAIL，且不再尝试读名单")
    void missingDispatchCapabilityFails() {
        Map<String, Object> result = build(new HealthyAdapter() {
            @Override
            public Set<DeviceCapability> capabilities() {
                return EnumSet.of(DeviceCapability.PARAM_READ, DeviceCapability.VERSION_READ);
            }
        }).check(device());

        assertEquals("FAIL", result.get("status"));
        assertEquals("FAIL", item(result, "capability").get("status"));
    }

    @Test
    @DisplayName("设备不通：读参数/版本/名单均为 FAIL，不抛异常")
    void offlineDeviceReportsFailures() {
        Map<String, Object> result = build(new HealthyAdapter() {
            @Override
            public JsonNode readParams(Device device) {
                throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "连接超时");
            }

            @Override
            public JsonNode readVersion(Device device) {
                throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "连接超时");
            }

            @Override
            public List<String> listPersonNos(Device device) {
                throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "连接超时");
            }
        }).check(device());

        assertEquals("FAIL", result.get("status"));
        assertEquals("FAIL", item(result, "params").get("status"));
        assertEquals("FAIL", item(result, "version").get("status"));
        assertEquals("FAIL", item(result, "persons").get("status"));
        assertTrue(String.valueOf(item(result, "params").get("detail")).contains("连接超时"));
    }

    @Test
    @DisplayName("通道无适配器：通道项 FAIL，其余项不影响")
    void unknownChannel() {
        Device cloud = device();
        cloud.setChannel(Device.Channel.CLOUD);
        Map<String, Object> result = build(new HealthyAdapter()).check(cloud);

        assertEquals("FAIL", item(result, "channel").get("status"));
    }

    /** 一个“什么都支持”的新设备适配器。 */
    static class HealthyAdapter implements DeviceAdapter {

        @Override
        public boolean supports(Device.Channel channel) {
            return channel == Device.Channel.LAN;
        }

        @Override
        public JsonNode readParams(Device device) {
            ObjectNode node = new ObjectMapper().createObjectNode();
            node.put("platformEnable", 0);
            node.put("heartBeatEnable", 0);
            node.put("heartBeatIp", "192.168.10.50");
            node.put("platformIp", "192.168.10.50");
            node.put("inout", 1);
            node.put("Comparison", 0);
            return node;
        }

        @Override
        public void writeParams(Device device, Map<String, Object> partial) {
        }

        @Override
        public void upsertPerson(Device device, PersonPayload payload) {
        }

        @Override
        public void removePerson(Device device, String personNo, String userType) {
        }

        @Override
        public void clearAllPersons(Device device) {
        }

        @Override
        public List<String> listPersonNos(Device device) {
            return List.of("V0001");
        }

        @Override
        public JsonNode readVersion(Device device) {
            ObjectNode node = new ObjectMapper().createObjectNode();
            node.put("model", "YC-P208-NEW");
            node.put("firmware", "V9.9.9");
            return node;
        }

        @Override
        public void openDoor(Device device) {
        }

        @Override
        public void showMessage(Device device, String message) {
        }

        @Override
        public void setDeviceTime(Device device, LocalDateTime time) {
        }
    }
}
