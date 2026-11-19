package com.fangke.visitor.device.service;

import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.callback.dto.HeartbeatPayload;
import com.fangke.visitor.device.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 心跳处理（docs/11 §1.1）。
 *
 * <p>设计要点：<b>无论校验结果如何都回 ok</b>。设备侧对非 0 结果会反复重试，
 * 而心跳失败没有业务价值，只会拖慢设备；异常一律走平台侧告警。
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    /** 时间偏差告警阈值（docs/10 §4：>60 秒告警，JOB-09）。 */
    private static final int CLOCK_SKEW_ALERT_SECONDS = 60;
    /** 容量水位告警阈值（BR-E-010）。 */
    private static final double CAPACITY_ALERT_RATE = 0.8;

    public record Outcome(boolean deviceKnown, boolean passwordOk, Integer clockSkewSeconds, boolean capacityAlert) {
    }

    private final DeviceRepository deviceRepository;
    private final AppProperties props;
    private final DeviceOnlineListener onlineListener;

    /** 单构造器：Spring 无需 @Autowired，也避免多构造器导致的注入歧义。 */
    public HeartbeatService(DeviceRepository deviceRepository, AppProperties props,
                            DeviceOnlineListener onlineListener) {
        this.deviceRepository = deviceRepository;
        this.props = props;
        this.onlineListener = onlineListener;
    }

    public Outcome handle(HeartbeatPayload payload, String rawJson) {
        Instant now = Instant.now();
        LocalDateTime heartbeatAt = LocalDateTime.ofInstant(now, ZoneId.systemDefault());

        Integer skew = null;
        Long deviceEpoch = payload.epochSeconds();
        if (deviceEpoch != null) {
            skew = (int) Math.abs(Duration.between(Instant.ofEpochSecond(deviceEpoch), now).getSeconds());
            if (skew > CLOCK_SKEW_ALERT_SECONDS) {
                log.warn("设备时钟偏差过大 SN={} skew={}s（阈值 {}s）",
                        payload.getSn(), skew, CLOCK_SKEW_ALERT_SECONDS);
            }
        }

        Device device = deviceRepository.findBySn(payload.getSn()).orElse(null);
        if (device == null) {
            log.warn("收到未登记设备的心跳 SN={} ip={}", payload.getSn(), payload.getIp());
            return new Outcome(false, false, skew, false);
        }

        boolean passwordOk = checkPassword(device, payload.getPassword());
        if (!passwordOk) {
            log.warn("设备心跳口令不匹配 SN={}（不影响应答）", payload.getSn());
        }
        boolean wasOnline = device.isOnline();

        boolean capacityAlert = false;
        if (payload.getPersonCount() != null && device.getCapacity() != null && device.getCapacity() > 0) {
            double rate = (double) payload.getPersonCount() / device.getCapacity();
            capacityAlert = rate >= CAPACITY_ALERT_RATE;
            if (capacityAlert) {
                log.warn("设备名单容量告警 SN={} personCount={} capacity={} rate={}",
                        payload.getSn(), payload.getPersonCount(), device.getCapacity(),
                        String.format("%.2f", rate));
            }
        }

        deviceRepository.updateHeartbeat(payload.getSn(), payload.getIp(), payload.getPersonCount(),
                heartbeatAt, skew, rawJson);

        // 离线 → 在线：触发一次对账（JOB-04 的“设备上线”分支）
        if (!wasOnline) {
            try {
                onlineListener.onDeviceOnline(device);
            } catch (Exception e) {
                log.warn("设备上线回调失败 SN={}：{}", payload.getSn(), e.getMessage());
            }
        }
        return new Outcome(true, passwordOk, skew, capacityAlert);
    }

    private boolean checkPassword(Device device, String passwordFromDevice) {
        if (!props.getDevice().isCallbackVerifyPassword()) {
            return true;
        }
        // 设备部分报文不带 password，此时只能依赖 IP 白名单（docs/11 §0.4）
        if (passwordFromDevice == null || passwordFromDevice.isBlank()) {
            return true;
        }
        String expected = device.getApiPassword();
        if (expected == null || expected.isBlank()) {
            expected = props.getDevice().getDefaultPassword();
        }
        return expected.equals(passwordFromDevice);
    }
}
