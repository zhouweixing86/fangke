package com.fangke.visitor.dispatch.service;

import com.fangke.visitor.admin.repository.AdminBlacklistRepository;
import com.fangke.visitor.appointment.repository.BlacklistRepository;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.pull.PullCommandRepository;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.security.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 黑名单下发：把「黑名单」这个身份推到设备的本地黑名单里。
 *
 * <p>为什么不复用白名单链路：设备侧名单是分类型的（{@code usertype=white/black}），
 * 平台下发时 {@code getDevicWhiteInfo} 会按 idno 判定类型。黑名单只影响"这个人来了要被拒"，
 * 与他是否有授权无关，所以单独一队指令。
 *
 * <p>只下发**身份证类型**的黑名单：设备是刷证/刷脸识别的，手机号在闸机上没法匹配。
 * 手机号拉黑仍按原有逻辑在"创建预约"时拦截。
 */
@Service
public class BlacklistDeviceDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BlacklistDeviceDispatcher.class);
    private static final int PRIORITY = 6;

    private final AdminBlacklistRepository blacklistRepository;
    private final DeviceRepository deviceRepository;
    private final PullCommandRepository commandRepository;
    private final CryptoService crypto;

    public BlacklistDeviceDispatcher(AdminBlacklistRepository blacklistRepository,
                                     DeviceRepository deviceRepository,
                                     PullCommandRepository commandRepository,
                                     CryptoService crypto) {
        this.blacklistRepository = blacklistRepository;
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.crypto = crypto;
    }

    /**
     * 拉黑生效：把该证件号作为黑名单下发到全部启用设备。
     *
     * @return 入队的设备数（0 表示没有设备或不是身份证类型）
     */
    public int push(Long blacklistId) {
        String idno = plainTargetOfIdCard(blacklistId);
        if (idno == null) {
            return 0;
        }
        String payload = payload(idno);
        int queued = 0;
        for (Device device : DeviceRepository.personDevices(deviceRepository.findAllActive())) {
            long id = commandRepository.enqueue(device.getId(),
                    PullCommandRepository.TYPE_ADD_PERSON, PRIORITY, payload, null);
            if (id > 0) {
                queued++;
            }
        }
        log.info("黑名单下发 id={} idno={} 入队设备 {} 台", blacklistId,
                com.fangke.visitor.device.pull.OpenApiRecordMapper.maskIdCard(idno), queued);
        return queued;
    }

    /**
     * 拉黑失效/恢复：从设备本地黑名单里删掉这个人。
     *
     * @return 入队的设备数
     */
    public int remove(Long blacklistId) {
        String idno = plainTargetOfIdCard(blacklistId);
        if (idno == null) {
            return 0;
        }
        String payload = payload(idno);
        int queued = 0;
        for (Device device : DeviceRepository.personDevices(deviceRepository.findAllActive())) {
            long id = commandRepository.enqueue(device.getId(),
                    PullCommandRepository.TYPE_DEL_PERSON, PRIORITY, payload, null);
            if (id > 0) {
                queued++;
            }
        }
        log.info("黑名单撤销下发 id={} idno={} 入队设备 {} 台", blacklistId,
                com.fangke.visitor.device.pull.OpenApiRecordMapper.maskIdCard(idno), queued);
        return queued;
    }

    /** 取身份证类型黑名单的明文证件号；手机号类型或解密失败返回 null。 */
    private String plainTargetOfIdCard(Long blacklistId) {
        if (blacklistId == null) {
            return null;
        }
        var row = blacklistRepository.findById(blacklistId).orElse(null);
        if (row == null || row.targetType() != BlacklistRepository.TARGET_ID_CARD) {
            return null;
        }
        byte[] enc = blacklistRepository.findTargetEnc(blacklistId).orElse(null);
        if (enc == null) {
            return null;
        }
        try {
            String plain = crypto.decrypt(enc);
            return plain == null || plain.isBlank() ? null : plain.trim().toUpperCase();
        } catch (Exception e) {
            log.warn("黑名单 {} 证件号解密失败，跳过设备下发：{}", blacklistId, e.getMessage());
            return null;
        }
    }

    /**
     * 设备指令负载：{@code personNo} 与 {@code idno} 都放证件号。
     *
     * <p>{@code personNo} 用于指令去重与回执匹配（设备回执只带 idno），
     * {@code usertype} 告诉设备这是黑名单。
     */
    private String payload(String idno) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("personNo", idno);
        map.put("idno", idno);
        map.put("usertype", "black");
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .valueToTree(map).toString();
    }
}
