package com.fangke.visitor.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 云通道适配器（MQTT）——P5 阶段实现。
 *
 * <p>协议见 docs/07。当前显式抛错而不是静默降级，避免现场误配 {@code channel=CLOUD}
 * 后以为是代码 bug。
 */
@Component
public class CloudMqttAdapter implements DeviceAdapter {

    private static final String TODO = "云通道（MQTT）尚未实现，见 docs/07 与 docs/09 §3.2 阶段五";

    @Override
    public boolean supports(Device.Channel channel) {
        return channel == Device.Channel.CLOUD;
    }

    /** 尚未实现，如实声明“零能力”，让调用方拿到明确提示而不是空指针。 */
    @Override
    public Set<DeviceCapability> capabilities() {
        return Collections.emptySet();
    }

    @Override
    public JsonNode readParams(Device device) {
        throw notImplemented();
    }

    @Override
    public void writeParams(Device device, Map<String, Object> partial) {
        throw notImplemented();
    }

    @Override
    public void upsertPerson(Device device, PersonPayload payload) {
        throw notImplemented();
    }

    @Override
    public void removePerson(Device device, String personNo, String userType) {
        throw notImplemented();
    }

    @Override
    public void clearAllPersons(Device device) {
        throw notImplemented();
    }

    @Override
    public List<String> listPersonNos(Device device) {
        throw notImplemented();
    }

    @Override
    public JsonNode readVersion(Device device) {
        throw notImplemented();
    }

    @Override
    public void openDoor(Device device) {
        throw notImplemented();
    }

    @Override
    public void showMessage(Device device, String message) {
        throw notImplemented();
    }

    @Override
    public void setDeviceTime(Device device, LocalDateTime time) {
        throw notImplemented();
    }

    private DeviceException notImplemented() {
        return new DeviceException(ErrorCode.DEVICE_PARAM_ERROR, TODO);
    }
}
