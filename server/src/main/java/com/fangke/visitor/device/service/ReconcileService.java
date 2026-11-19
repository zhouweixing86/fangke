package com.fangke.visitor.device.service;

import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.PersonPayload;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 名单对账（docs/02 JOB-04、docs/10 §7）。
 *
 * <p>三向比对：
 * <ul>
 *   <li>平台要、设备有 → 一致</li>
 *   <li>平台要、设备没有 → 记为缺失（P1 只记录并告警；补推需要明文证件号，等 M4 访客档案补齐）</li>
 *   <li>平台不要、设备有 → <b>直接删除</b>（安全优先：不该在名单里的人必须尽快清除）</li>
 * </ul>
 */
@Service
public class ReconcileService implements DeviceOnlineListener {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Result(String deviceSn,
                         int expectedCount,
                         int actualCount,
                         List<String> missingInDevice,
                         List<String> extraInDevice,
                         int removed,
                         int failedRemoval,
                         String finishedAt) {
    }

    private final DeviceRepository deviceRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;
    private final ExpectedPersonPort expectedPersonPort;
    private final PersonStateStore stateStore;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "device-reconcile");
        t.setDaemon(true);
        return t;
    });

    public ReconcileService(DeviceRepository deviceRepository,
                            DeviceAdapterFactory adapterFactory,
                            DeviceGateway gateway,
                            ExpectedPersonPort expectedPersonPort,
                            PersonStateStore stateStore) {
        this.deviceRepository = deviceRepository;
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
        this.expectedPersonPort = expectedPersonPort;
        this.stateStore = stateStore;
    }

    @Override
    public void onDeviceOnline(Device device) {
        if (device.getId() == null) {
            return;
        }
        Long deviceId = device.getId();
        executor.submit(() -> {
            try {
                Result result = reconcile(deviceId);
                log.info("设备上线自动对账完成 device={} expected={} actual={} missing={} extra={} removed={}",
                        result.deviceSn(), result.expectedCount(), result.actualCount(),
                        result.missingInDevice().size(), result.extraInDevice().size(), result.removed());
            } catch (Exception e) {
                log.warn("设备上线自动对账失败 deviceId={}：{}", deviceId, e.getMessage());
            }
        });
    }

    public Result reconcile(Long deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在：" + deviceId));
        DeviceAdapter adapter = adapterFactory.resolve(device);

        List<String> actualNos = gateway.execute(device, "listPersonNos", adapter::listPersonNos);
        Map<String, String> actual = new LinkedHashMap<>();
        for (String no : actualNos) {
            actual.put(normalize(no), PersonPayload.USER_TYPE_VISITOR);
        }

        List<ExpectedPersonPort.ExpectedPerson> expectedList =
                expectedPersonPort.expectedOn(device.getGateId(), Instant.now());
        Map<String, String> expected = new LinkedHashMap<>();
        for (ExpectedPersonPort.ExpectedPerson p : expectedList) {
            expected.put(normalize(p.personNo()), p.userType());
        }

        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        int removed = 0;
        int failedRemoval = 0;

        for (Map.Entry<String, String> e : expected.entrySet()) {
            String personNo = e.getKey();
            if (actual.containsKey(personNo)) {
                stateStore.save(deviceId, personNo, PersonStateStore.EXPECT_IN,
                        PersonStateStore.ACTUAL_IN, false);
            } else {
                missing.add(personNo);
                stateStore.save(deviceId, personNo, PersonStateStore.EXPECT_IN,
                        PersonStateStore.ACTUAL_OUT, true);
            }
        }

        for (Map.Entry<String, String> e : actual.entrySet()) {
            String personNo = e.getKey();
            if (expected.containsKey(personNo)) {
                continue;
            }
            extra.add(personNo);
            stateStore.save(deviceId, personNo, PersonStateStore.EXPECT_OUT,
                    PersonStateStore.ACTUAL_IN, true);
            String userType = e.getValue();
            try {
                gateway.execute(device, "removePerson:" + personNo,
                        d -> {
                            adapter.removePerson(d, personNo, userType);
                            return null;
                        });
                removed++;
                stateStore.save(deviceId, personNo, PersonStateStore.EXPECT_OUT,
                        PersonStateStore.ACTUAL_OUT, false);
            } catch (Exception ex) {
                failedRemoval++;
                log.warn("对账删除失败 device={} personNo={}：{}", device.getDeviceSn(), personNo, ex.getMessage());
            }
        }

        if (!missing.isEmpty()) {
            log.warn("对账发现设备缺少 {} 人（待补下发）device={} sample={}",
                    missing.size(), device.getDeviceSn(), missing.subList(0, Math.min(5, missing.size())));
        }

        return new Result(device.getDeviceSn(), expected.size(), actual.size(),
                missing, extra, removed, failedRemoval, DATETIME.format(LocalDateTime.now()));
    }

    /** 设备侧人员编号可能存在大小写/空格差异。 */
    private String normalize(String personNo) {
        return personNo == null ? "" : personNo.trim();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
