package com.fangke.visitor.device.adapter;

import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.model.Device;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按设备档案的 {@code channel} 选择适配器（docs/09 §2.1）。
 *
 * <p>通道切换只改配置，不改业务代码。
 */
@Component
public class DeviceAdapterFactory {

    private final List<DeviceAdapter> adapters;

    public DeviceAdapterFactory(List<DeviceAdapter> adapters) {
        this.adapters = adapters;
    }

    public DeviceAdapter resolve(Device device) {
        Device.Channel channel = device.getChannel() == null ? Device.Channel.PULL : device.getChannel();
        return adapters.stream()
                .filter(a -> a.supports(channel))
                .findFirst()
                .orElseThrow(() -> new DeviceException(ErrorCode.DEVICE_PARAM_ERROR,
                        "未找到可用设备通道：" + channel));
    }
}
