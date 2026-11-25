package com.fangke.visitor.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 设备能力裁决（docs/12）。
 *
 * <p>生效能力 = <b>适配器声明</b> ∩ <b>设备档案覆盖</b>：
 * <ul>
 *   <li>适配器声明：这个协议的固件天生能做什么（{@link DeviceAdapter#capabilities()}）；</li>
 *   <li>设备档案覆盖：{@code device.capability} 里按台微调，支持
 *       {@code {"disable":[...]}} 与 {@code {"only":[...]}}（也接受裸数组，等价于 only）。</li>
 * </ul>
 * 业务调用前先 {@link #require}，不支持的指令会变成 4006 明确提示，而不是 500 或静默失败。
 */
@Service
public class DeviceCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCapabilityService.class);

    private final DeviceAdapterFactory adapterFactory;
    private final com.fangke.visitor.config.AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeviceCapabilityService(DeviceAdapterFactory adapterFactory,
                                   com.fangke.visitor.config.AppProperties props) {
        this.adapterFactory = adapterFactory;
        this.props = props;
    }

    /** 单台设备当前真正可用的能力集。 */
    public Set<DeviceCapability> effective(Device device) {
        Set<DeviceCapability> base = EnumSet.noneOf(DeviceCapability.class);
        try {
            base.addAll(adapterFactory.resolve(device).capabilities());
        } catch (DeviceException e) {
            // 通道没有可用适配器（例如 CLOUD 未实现）时按“零能力”处理，由调用方给出提示
            log.warn("设备通道无可用适配器 device={} channel={}", device.getDeviceSn(), device.getChannel());
        }
        // 车闸相机实际走的是「相机连我们云端 broker」的 MQTT 通道，适配器那套能力位反映不了
        // 「平台下发名单 / 远程开闸」，按通道补上，否则管理端会显示不支持。
        if (device.getChannel() == Device.Channel.VEHICLE && props.getVehicle().getMqtt().isEnabled()) {
            base.add(DeviceCapability.PLATE_UPSERT);
            base.add(DeviceCapability.PLATE_REMOVE);
            base.add(DeviceCapability.DOOR_OPEN);
        }
        return applyOverride(base, device);
    }

    public boolean supports(Device device, DeviceCapability capability) {
        return effective(device).contains(capability);
    }

    /** 不支持则抛出 4006，带上设备型号与能力中文名，便于现场判断是固件问题还是型号问题。 */
    public void require(Device device, DeviceCapability capability, String action) {
        if (!supports(device, capability)) {
            throw new DeviceException(ErrorCode.DEVICE_UNSUPPORTED,
                    "设备 " + device.getDeviceSn() + "（型号 " + nullSafe(device.getModel()) + "，通道 "
                            + device.getChannel() + "）不支持「" + capability.label() + "」，无法执行" + action
                            + "；请核对设备型号/固件，或在设备档案的 capability 中调整能力配置");
        }
    }

    /** 管理端展示用：全部能力位的支持情况。 */
    public List<Map<String, Object>> describe(Device device) {
        Set<DeviceCapability> effective = effective(device);
        Set<DeviceCapability> adapterLevel = EnumSet.noneOf(DeviceCapability.class);
        try {
            adapterLevel.addAll(adapterFactory.resolve(device).capabilities());
        } catch (DeviceException ignored) {
            // 同上
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (DeviceCapability capability : DeviceCapability.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", capability.name());
            item.put("label", capability.label());
            item.put("supported", effective.contains(capability));
            item.put("byAdapter", adapterLevel.contains(capability));
            list.add(item);
        }
        return list;
    }

    /** 缺失的关键能力（交接检查用）。 */
    public Set<DeviceCapability> missingDispatchCapabilities(Device device) {
        Set<DeviceCapability> effective = effective(device);
        return DeviceCapability.dispatchRequired().stream()
                .filter(c -> !effective.contains(c))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DeviceCapability.class)));
    }

    private Set<DeviceCapability> applyOverride(Set<DeviceCapability> base, Device device) {
        String raw = device.getCapabilityJson();
        if (raw == null || raw.isBlank()) {
            return base;
        }
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (Exception e) {
            log.warn("device.capability 不是合法 JSON，按全量能力处理 device={} raw={}", device.getDeviceSn(), raw);
            return base;
        }

        Set<DeviceCapability> result = EnumSet.copyOf(base.isEmpty() ? EnumSet.noneOf(DeviceCapability.class) : base);
        JsonNode only = node.isArray() ? node : node.get("only");
        if (only != null && only.isArray()) {
            Set<DeviceCapability> allowed = readNames(only);
            result.retainAll(allowed);
        }
        JsonNode disable = node.get("disable");
        if (disable != null && disable.isArray()) {
            result.removeAll(readNames(disable));
        }
        return result;
    }

    private Set<DeviceCapability> readNames(JsonNode array) {
        Set<DeviceCapability> set = EnumSet.noneOf(DeviceCapability.class);
        for (JsonNode item : array) {
            String name = item.asText("").trim().toUpperCase();
            if (name.isEmpty()) {
                continue;
            }
            try {
                set.add(DeviceCapability.valueOf(name));
            } catch (IllegalArgumentException e) {
                log.warn("device.capability 含未知能力位：{}", name);
            }
        }
        return set;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
