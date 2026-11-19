package com.fangke.visitor.guard.service;

import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.service.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通过门点下的设备远程开门（BR-V-003 / BR-V-030）。
 */
@Component
public class DeviceDoorOpener implements DoorOpener {

    private static final Logger log = LoggerFactory.getLogger(DeviceDoorOpener.class);

    private final DeviceRepository deviceRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;

    public DeviceDoorOpener(DeviceRepository deviceRepository,
                            DeviceAdapterFactory adapterFactory,
                            DeviceGateway gateway) {
        this.deviceRepository = deviceRepository;
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
    }

    @Override
    public int open(Long gateId) {
        List<Device> devices = deviceRepository.findActiveByGate(gateId);
        if (devices.isEmpty()) {
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "门点下没有可用设备");
        }
        int success = 0;
        for (Device device : devices) {
            try {
                DeviceAdapter adapter = adapterFactory.resolve(device);
                gateway.execute(device, "openDoor", d -> {
                    adapter.openDoor(d);
                    return null;
                });
                success++;
            } catch (Exception e) {
                log.warn("开门失败 device={}：{}", device.getDeviceSn(), e.getMessage());
            }
        }
        if (success == 0) {
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "全部设备开门失败");
        }
        log.info("远程开门成功 gate={} 设备数={}", gateId, success);
        return success;
    }
}
