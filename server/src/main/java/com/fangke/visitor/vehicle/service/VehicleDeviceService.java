package com.fangke.visitor.vehicle.service;

import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.gate.service.GateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 车闸相机档案（docs/18）。
 *
 * <p>车闸相机与 YC-P208 不是一个牌子、也不共用通道，所以单独建档：
 * {@code channel=VEHICLE}，管理端按通道单独列出。
 *
 * <p>首次收到 {@code /openapi/v1/vehicleEvent} 时自动登记到「待分配」门点，
 * 现场必须在设备管理里分配到真实门点 + 方向，平台才敢按门点判定放行。
 */
@Service
public class VehicleDeviceService {

    private static final Logger log = LoggerFactory.getLogger(VehicleDeviceService.class);

    private final DeviceRepository deviceRepository;
    private final GateService gateService;

    public VehicleDeviceService(DeviceRepository deviceRepository, GateService gateService) {
        this.deviceRepository = deviceRepository;
        this.gateService = gateService;
    }

    /**
     * 按 SN 找车闸相机；没有就自动登记一台。
     *
     * <p>已存在但通道不是 VEHICLE（历史登记成人行终端）时不改通道，交给现场在档案里修正，
     * 避免一次上报就把同 SN 的设备类型改错。
     */
    public Device resolveOrRegister(String deviceSn, String ip) {
        if (deviceSn == null || deviceSn.isBlank()) {
            return null;
        }
        String sn = deviceSn.trim();
        Optional<Device> existing = deviceRepository.findBySn(sn);
        if (existing.isPresent()) {
            Device device = existing.get();
            if (device.getChannel() != Device.Channel.VEHICLE) {
                log.warn("车牌事件来自非车闸通道的设备 sn={} channel={}（如属误登记，请在设备档案里改成车闸相机）",
                        sn, device.getChannel());
            }
            deviceRepository.updateHeartbeat(sn, ip, null, LocalDateTime.now(), null, null);
            return device;
        }
        Device device = new Device();
        device.setDeviceSn(sn);
        // mac 有唯一索引，先用 SN 生成占位；车闸相机通常不上报 mac
        device.setMac(placeholderMac(sn));
        device.setDeviceName("新相机-" + sn);
        device.setChannel(Device.Channel.VEHICLE);
        device.setModel("车闸相机");
        device.setGateId(gateService.unassignedGateId());
        device.setDirection(1);
        device.setIp(ip);
        device.setPort(8091);
        device.setStatus(1);
        long id = deviceRepository.register(device);
        device.setId(id);
        deviceRepository.updateHeartbeat(sn, ip, null, LocalDateTime.now(), null, null);
        device.setOnline(true);
        log.warn("自动登记车闸相机 sn={} ip={}（已放入「待分配」，请分配门点与方向）", sn, ip);
        return device;
    }

    /** 车闸相机列表（可按门点过滤）。 */
    public List<Device> list(Long gateId) {
        List<Device> all = deviceRepository.findActiveByChannel(Device.Channel.VEHICLE);
        if (gateId == null) {
            return all;
        }
        return all.stream().filter(d -> gateId.equals(d.getGateId())).toList();
    }

    private String placeholderMac(String sn) {
        String tail = sn.length() <= 29 ? sn : sn.substring(sn.length() - 29);
        return "VH-" + tail;
    }
}
