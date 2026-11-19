package com.fangke.visitor.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备接入自检（docs/12 §3）：把「换设备后到底能不能用」变成一次点击就能看到的清单。
 *
 * <p>逐项独立执行，单项失败不影响其它项；每项给出 OK / WARN / FAIL 与现场可读的原因，
 * 便于实施人员拿着结果直接找厂商对参数。
 */
@Service
public class DeviceSelfCheckService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSelfCheckService.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 设备参数里平台依赖的字段（docs/06 §0.2 实测字段名）。
     *
     * <p>注意现机没有 {@code heartBeatUrl}，用的是 {@code heartBeatIp}/{@code platformIp} +
     * 端口分开配；换设备时字段名极可能不同，所以这里只做「缺字段」提示，不当作失败。
     */
    private static final List<String> REQUIRED_PARAMS =
            List.of("platformEnable", "heartBeatEnable", "heartBeatIp", "platformIp", "inout");

    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;
    private final DeviceCapabilityService capabilityService;

    public DeviceSelfCheckService(DeviceAdapterFactory adapterFactory,
                                  DeviceGateway gateway,
                                  DeviceCapabilityService capabilityService) {
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
        this.capabilityService = capabilityService;
    }

    public Map<String, Object> check(Device device) {
        List<Map<String, Object>> items = new ArrayList<>();

        Set<DeviceCapability> effective;
        try {
            effective = capabilityService.effective(device);
        } catch (RuntimeException e) {
            effective = Set.of();
        }
        Set<DeviceCapability> missing = capabilityService.missingDispatchCapabilities(device);
        items.add(item("capability", "能力覆盖",
                missing.isEmpty() ? "OK" : "FAIL",
                missing.isEmpty()
                        ? "名单下发所需能力齐备：" + DeviceCapability.dispatchRequired()
                        : "缺少名单下发关键能力：" + missing));

        DeviceAdapter adapter = null;
        try {
            adapter = adapterFactory.resolve(device);
            items.add(item("channel", "设备通道", "OK",
                    "通道 " + device.getChannel() + " 已解析到适配器 " + adapter.getClass().getSimpleName()));
        } catch (DeviceException e) {
            items.add(item("channel", "设备通道", "FAIL", e.getMessage()));
        }

        if (adapter != null) {
            if (effective.contains(DeviceCapability.VERSION_READ)) {
                items.add(readVersion(device, adapter));
            }
            if (effective.contains(DeviceCapability.PARAM_READ)) {
                items.add(readParams(device, adapter));
            }
            if (effective.contains(DeviceCapability.PERSON_LIST)) {
                items.add(readPersons(device, adapter));
            }
        }

        boolean failed = items.stream().anyMatch(i -> "FAIL".equals(i.get("status")));
        boolean warned = items.stream().anyMatch(i -> "WARN".equals(i.get("status")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceSn", nullSafe(device.getDeviceSn()));
        result.put("model", nullSafe(device.getModel()));
        result.put("firmware", nullSafe(device.getFirmware()));
        result.put("channel", device.getChannel() == null ? "" : device.getChannel().name());
        result.put("checkedAt", DATETIME.format(LocalDateTime.now()));
        result.put("status", failed ? "FAIL" : warned ? "WARN" : "OK");
        result.put("capabilities", capabilityService.describe(device));
        result.put("items", items);
        return result;
    }

    private Map<String, Object> readVersion(Device device, DeviceAdapter adapter) {
        try {
            JsonNode version = gateway.execute(device, "selfcheck.readVersion", adapter::readVersion);
            String model = text(version, "model");
            String firmware = text(version, "firmware");
            return item("version", "固件版本", "OK",
                    "model=" + nullSafe(model) + " firmware=" + nullSafe(firmware));
        } catch (RuntimeException e) {
            return item("version", "固件版本", "FAIL", reason(e));
        }
    }

    private Map<String, Object> readParams(Device device, DeviceAdapter adapter) {
        try {
            JsonNode params = gateway.execute(device, "selfcheck.readParams", adapter::readParams);
            List<String> absent = new ArrayList<>();
            for (String key : REQUIRED_PARAMS) {
                if (params == null || params.get(key) == null) {
                    absent.add(key);
                }
            }
            if (params == null) {
                return item("params", "参数读取", "FAIL", "设备未返回参数");
            }
            if (absent.isEmpty()) {
                return item("params", "参数读取", "OK",
                        "platformEnable=" + params.path("platformEnable").asText("")
                                + "，heartBeatEnable=" + params.path("heartBeatEnable").asText("")
                                + "，heartBeatIp=" + params.path("heartBeatIp").asText("")
                                + "，inout=" + params.path("inout").asText("")
                                + "，Comparison=" + params.path("Comparison").asText("-"));
            }
            return item("params", "参数读取", "WARN",
                    "缺少平台依赖的参数字段：" + absent + "（换设备时需确认新协议对应字段）");
        } catch (RuntimeException e) {
            return item("params", "参数读取", "FAIL", reason(e));
        }
    }

    private Map<String, Object> readPersons(Device device, DeviceAdapter adapter) {
        try {
            List<String> persons = gateway.execute(device, "selfcheck.listPersons", adapter::listPersonNos);
            int capacity = device.getCapacity() == null ? 0 : device.getCapacity();
            int count = persons == null ? 0 : persons.size();
            String status = capacity > 0 && count >= capacity ? "WARN" : "OK";
            return item("persons", "名单读取", status,
                    "设备当前 " + count + " 人" + (capacity > 0 ? "（容量 " + capacity + "）" : ""));
        } catch (RuntimeException e) {
            return item("persons", "名单读取", "FAIL", reason(e));
        }
    }

    private Map<String, Object> item(String key, String label, String status, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("status", status);
        item.put("detail", detail);
        return item;
    }

    private String reason(RuntimeException e) {
        log.debug("自检项失败", e);
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
