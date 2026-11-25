package com.fangke.visitor.vehicle.mqtt;

import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.vehicle.service.VehicleDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 车闸车牌自动跟随（docs/23）：定时把「员工车牌 + 当天已授权访客车牌」差分下发到每台车闸相机，
 * 停用员工、过期访客的车牌会在下一轮被删掉。
 *
 * <p>用定时对账而不是在业务点里埋下发：车牌来源分散（员工档案、预约审批、现场登记），
 * 逐个埋点容易漏；对账只依赖「期望清单 + 上次下发指纹」，任何来源都会自动跟上。
 * 相机上线（{@code Conn}）时还会额外对齐一次，所以现场断网恢复后不用等满一轮。
 */
@Component
public class VehicleMqttPlateSyncJob {

    private static final Logger log = LoggerFactory.getLogger(VehicleMqttPlateSyncJob.class);

    private final VehicleMqttService mqttService;
    private final VehicleDeviceService deviceService;

    public VehicleMqttPlateSyncJob(VehicleMqttService mqttService, VehicleDeviceService deviceService) {
        this.mqttService = mqttService;
        this.deviceService = deviceService;
    }

    @Scheduled(fixedDelayString = "${fangke.vehicle.mqtt.sync-ms:20000}",
            initialDelayString = "${fangke.vehicle.mqtt.sync-initial-delay-ms:20000}")
    public void run() {
        if (!mqttService.ready()) {
            return;
        }
        for (Device device : deviceService.list(null)) {
            try {
                mqttService.syncDevicePlates(device.getDeviceSn(), device.getGateId());
            } catch (Exception e) {
                log.warn("车闸车牌自动下发失败 device={}：{}", device.getDeviceSn(), e.getMessage());
            }
        }
    }
}
