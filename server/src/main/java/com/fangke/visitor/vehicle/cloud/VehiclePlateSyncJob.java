package com.fangke.visitor.vehicle.cloud;

import com.fangke.visitor.vehicle.service.VehiclePlateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 车闸车牌自动跟随（docs/19 §3）：定时把「员工 + 当天已授权访客」的车牌差分下发到云车场。
 *
 * <p>为什么用定时对账而不是在业务点里埋下发：车牌来源分散（员工档案、预约审批、现场登记），
 * 逐个埋点容易漏；对账只依赖"期望清单"和"上次下发快照"，任何来源都会在下一轮自动跟上。
 * 间隔由 {@code fangke.vehicle.cloud.sync-ms} 控制（默认 60 秒），未启用时直接跳过。
 */
@Component
public class VehiclePlateSyncJob {

    private static final Logger log = LoggerFactory.getLogger(VehiclePlateSyncJob.class);

    private final VehiclePlateService plateService;
    private final VehiclePlateCloudService cloudService;

    public VehiclePlateSyncJob(VehiclePlateService plateService, VehiclePlateCloudService cloudService) {
        this.plateService = plateService;
        this.cloudService = cloudService;
    }

    @Scheduled(fixedDelayString = "${fangke.vehicle.cloud.sync-ms:60000}",
            initialDelayString = "${fangke.vehicle.cloud.sync-initial-delay-ms:45000}")
    public void run() {
        if (!cloudService.enabled()) {
            return;
        }
        try {
            VehiclePlateCloudService.SyncResult result = cloudService.reconcile(plateService.snapshot(null));
            if (result.failed() > 0) {
                log.warn("车闸车牌自动下发有失败：{} {}", result.failed(), result.errors());
            }
        } catch (Exception e) {
            log.warn("车闸车牌自动下发异常：{}", e.getMessage());
        }
    }
}
