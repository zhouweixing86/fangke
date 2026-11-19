package com.fangke.visitor.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置模拟设备（{@code device.channel=MOCK}）。
 *
 * <p>用途有两个：
 * <ol>
 *   <li>没有真机时也能把「审批 → 下发 → 名单 → 记录」整条链路跑通，前端联调不再等硬件；</li>
 *   <li>新设备适配器写好后，用它做行为对拍：同一份报文两边都接受，说明语义一致。</li>
 * </ol>
 * 名单只存在内存里，进程重启即清空；不会写入任何业务表。
 */
@Component
public class MockDeviceAdapter implements DeviceAdapter {

    private static final Logger log = LoggerFactory.getLogger(MockDeviceAdapter.class);

    private final ObjectMapper mapper = new ObjectMapper();
    /** deviceSn -> (personNo -> 报文快照) */
    private final Map<String, Map<String, String>> persons = new ConcurrentHashMap<>();

    @Override
    public boolean supports(Device.Channel channel) {
        return channel == Device.Channel.MOCK;
    }

    @Override
    public Set<DeviceCapability> capabilities() {
        return EnumSet.allOf(DeviceCapability.class);
    }

    @Override
    public JsonNode readParams(Device device) {
        ObjectNode params = mapper.createObjectNode();
        params.put("platformEnable", 0);
        params.put("heartBeatEnable", 0);
        params.put("heartBeatIp", device.getIp() == null ? "127.0.0.1" : device.getIp());
        params.put("platformIp", device.getIp() == null ? "127.0.0.1" : device.getIp());
        params.put("inout", device.getDirection() == null ? 1 : device.getDirection());
        params.put("personCount", persons.getOrDefault(sn(device), Map.of()).size());
        return params;
    }

    @Override
    public void writeParams(Device device, Map<String, Object> partial) {
        log.info("[MOCK] writeParams device={} keys={}", sn(device), partial.keySet());
    }

    @Override
    public void upsertPerson(Device device, PersonPayload payload) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("personNo", payload.getPersonNo());
        snapshot.put("name", payload.getName());
        snapshot.put("userType", payload.getUserType());
        snapshot.put("validFrom", String.valueOf(payload.getValidFrom()));
        snapshot.put("validTo", String.valueOf(payload.getValidTo()));
        snapshot.put("withPhoto", payload.requiresPhoto());
        persons.computeIfAbsent(sn(device), k -> new ConcurrentHashMap<>())
                .put(payload.getPersonNo(), snapshot.toString());
        log.info("[MOCK] upsertPerson device={} personNo={} total={}",
                sn(device), payload.getPersonNo(), persons.get(sn(device)).size());
    }

    @Override
    public void removePerson(Device device, String personNo, String userType) {
        Map<String, String> box = persons.get(sn(device));
        if (box != null) {
            box.remove(personNo);
        }
        log.info("[MOCK] removePerson device={} personNo={}", sn(device), personNo);
    }

    @Override
    public void clearAllPersons(Device device) {
        persons.remove(sn(device));
        log.info("[MOCK] clearAllPersons device={}", sn(device));
    }

    @Override
    public List<String> listPersonNos(Device device) {
        return new ArrayList<>(persons.getOrDefault(sn(device), Map.of()).keySet());
    }

    @Override
    public JsonNode readVersion(Device device) {
        ObjectNode version = mapper.createObjectNode();
        version.put("model", "MOCK-DEVICE");
        version.put("firmware", "0.0.0-MOCK");
        version.put("deviceSn", sn(device));
        return version;
    }

    @Override
    public void openDoor(Device device) {
        log.info("[MOCK] openDoor device={} at={}", sn(device), LocalDateTime.now());
    }

    @Override
    public void showMessage(Device device, String message) {
        log.info("[MOCK] showMessage device={} message={}", sn(device), message);
    }

    @Override
    public void setDeviceTime(Device device, LocalDateTime time) {
        log.info("[MOCK] setDeviceTime device={} time={}", sn(device), time);
    }

    private String sn(Device device) {
        return device.getDeviceSn() == null ? String.valueOf(device.getId()) : device.getDeviceSn();
    }
}
