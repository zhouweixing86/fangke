package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.Device;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存设备仓储：不依赖数据库，便于对服务层做纯单测。
 */
public class InMemoryDeviceRepository implements DeviceRepository {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final List<String> heartbeatLog = new ArrayList<>();

    public InMemoryDeviceRepository put(Device device) {
        devices.put(device.getDeviceSn(), device);
        return this;
    }

    public List<String> heartbeatLog() {
        return heartbeatLog;
    }

    @Override
    public Optional<Device> findBySn(String deviceSn) {
        return Optional.ofNullable(devices.get(deviceSn));
    }

    @Override
    public Optional<Device> findById(Long id) {
        return devices.values().stream().filter(d -> d.getId().equals(id)).findFirst();
    }

    @Override
    public Optional<Device> findByMac(String mac) {
        return devices.values().stream().filter(d -> mac.equalsIgnoreCase(d.getMac())).findFirst();
    }

    @Override
    public List<Device> findActiveByGate(Long gateId) {
        return devices.values().stream()
                .filter(d -> d.getGateId() != null && d.getGateId().equals(gateId))
                .toList();
    }

    @Override
    public List<Device> findAllActive() {
        return devices.values().stream().filter(d -> d.getStatus() == 1).toList();
    }

    @Override
    public List<Device> findActiveByChannel(Device.Channel channel) {
        return devices.values().stream()
                .filter(d -> d.getStatus() == 1 && d.getChannel() == channel)
                .toList();
    }

    @Override
    public void updateHeartbeat(String deviceSn, String ip, Integer personCount,
                                LocalDateTime heartbeatAt, Integer clockSkewSeconds, String rawJson) {
        Device device = devices.get(deviceSn);
        if (device == null) {
            return;
        }
        device.setOnline(true);
        device.setLastHeartbeat(heartbeatAt);
        if (personCount != null) {
            device.setPersonCount(personCount);
        }
        if (ip != null) {
            device.setIp(ip);
        }
        heartbeatLog.add(deviceSn + "|" + clockSkewSeconds);
    }

    @Override
    public void updateFirmware(String deviceSn, String firmware, String model) {
        Device device = devices.get(deviceSn);
        if (device != null) {
            device.setFirmware(firmware);
            device.setModel(model);
        }
    }

    @Override
    public long register(Device device) {
        long id = devices.size() + 1L;
        device.setId(id);
        devices.put(device.getDeviceSn(), device);
        return id;
    }

    @Override
    public void bindMac(Long deviceId, String mac) {
        devices.values().stream()
                .filter(d -> deviceId.equals(d.getId()))
                .findFirst()
                .ifPresent(d -> d.setMac(mac));
    }

    @Override
    public void updateMeta(Long deviceId, String deviceName, String model, Integer direction,
                           Long gateId, String remark) {
        devices.values().stream()
                .filter(d -> deviceId.equals(d.getId()))
                .findFirst()
                .ifPresent(d -> {
                    if (deviceName != null) {
                        d.setDeviceName(deviceName);
                    }
                    if (model != null) {
                        d.setModel(model);
                    }
                    if (direction != null) {
                        d.setDirection(direction);
                    }
                    if (gateId != null) {
                        d.setGateId(gateId);
                    }
                    if (remark != null) {
                        d.setRemark(remark);
                    }
                });
    }

    @Override
    public void updateChannel(Long deviceId, Device.Channel channel) {
        devices.values().stream()
                .filter(d -> deviceId.equals(d.getId()))
                .findFirst()
                .ifPresent(d -> d.setChannel(channel));
    }

    @Override
    public int countByGate(Long gateId) {
        return (int) devices.values().stream()
                .filter(d -> gateId.equals(d.getGateId()) && d.getStatus() == 1)
                .count();
    }

    @Override
    public void deleteById(Long deviceId) {
        devices.values().removeIf(d -> d.getId().equals(deviceId));
    }
}
