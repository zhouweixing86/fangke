package com.fangke.visitor.device.service;

import com.fangke.visitor.device.callback.dto.ComparePayload;
import com.fangke.visitor.device.callback.dto.CompareResponse;
import com.fangke.visitor.device.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 在线比对（docs/11 §1.3）：设备把扫到的码/证件号实时发来，平台同步返回是否开门。
 *
 * <p>性能约束：P99 &lt; 300ms，**不得访问远程服务**。当前实现只查本地库，
 * 上线前需在 {@link GrantChecker} 前加两级缓存。
 */
@Service
public class OnlineCompareService {

    private static final Logger log = LoggerFactory.getLogger(OnlineCompareService.class);

    private final DeviceRepository deviceRepository;
    private final QrCodeVerifier qrCodeVerifier;
    private final GrantChecker grantChecker;

    public OnlineCompareService(DeviceRepository deviceRepository,
                                QrCodeVerifier qrCodeVerifier,
                                GrantChecker grantChecker) {
        this.deviceRepository = deviceRepository;
        this.qrCodeVerifier = qrCodeVerifier;
        this.grantChecker = grantChecker;
    }

    public CompareResponse compare(ComparePayload payload) {
        Device device = resolveDevice(payload);
        if (device == null) {
            log.warn("在线比对来自未登记设备 sn={} mac={}", payload.getDeviceSn(), payload.getDevSno());
            return CompareResponse.reject(CompareResponse.NO_GRANT, "设备未登记");
        }

        String type = payload.getType() == null ? "" : payload.getType().trim();
        return switch (type) {
            case "qr" -> compareQr(device, payload.getValue());
            case "id" -> CompareResponse.reject(CompareResponse.NO_GRANT, "身份证核验未接入");
            case "face" -> CompareResponse.reject(CompareResponse.NO_GRANT, "人脸核验未接入");
            case "ic" -> CompareResponse.reject(CompareResponse.NO_GRANT, "刷卡核验未接入");
            case "stranger" -> CompareResponse.reject(CompareResponse.NO_GRANT, "陌生人不放行");
            default -> CompareResponse.invalid();
        };
    }

    private CompareResponse compareQr(Device device, String code) {
        Instant now = Instant.now();
        QrCodeVerifier.Outcome outcome = qrCodeVerifier.verify(code, now);
        switch (outcome.result()) {
            case INVALID_FORMAT, BAD_SIGNATURE -> {
                log.info("二维码校验失败 device={} reason={}", device.getDeviceSn(), outcome.result());
                return CompareResponse.invalid();
            }
            case EXPIRED -> {
                log.info("二维码已过期 device={} expireAt={}", device.getDeviceSn(), outcome.expireAt());
                return CompareResponse.expired();
            }
            default -> {
                // 继续做授权判定
            }
        }

        GrantChecker.State state = grantChecker.check(outcome.appointmentNo(), device.getGateId(), now);
        switch (state) {
            case NO_GRANT -> {
                log.info("二维码无授权 device={} appointment={}", device.getDeviceSn(), outcome.appointmentNo());
                return CompareResponse.noGrant();
            }
            case OUT_OF_WINDOW -> {
                log.info("不在授权时段 device={} appointment={}", device.getDeviceSn(), outcome.appointmentNo());
                return CompareResponse.outOfWindow();
            }
            case GATE_MISMATCH -> {
                log.warn("门点不符 device={} appointment={}", device.getDeviceSn(), outcome.appointmentNo());
                return CompareResponse.gateMismatch();
            }
            case OK -> {
                log.info("二维码放行 device={} appointment={}", device.getDeviceSn(), outcome.appointmentNo());
                return CompareResponse.pass("识别成功");
            }
            default -> {
                return CompareResponse.noGrant();
            }
        }
    }

    private Device resolveDevice(ComparePayload payload) {
        String sn = payload.resolveDeviceSn();
        if (sn != null) {
            Device device = deviceRepository.findBySn(sn).orElse(null);
            if (device != null) {
                return device;
            }
        }
        // 部分固件只上报 MAC（dev_sno）
        if (payload.getDevSno() == null || payload.getDevSno().isBlank()) {
            return null;
        }
        return deviceRepository.findByMac(payload.getDevSno()).orElse(null);
    }
}
