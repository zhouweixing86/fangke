package com.fangke.visitor.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fangke.visitor.device.callback.dto.RecordPayload;
import com.fangke.visitor.device.model.AccessEvent;
import com.fangke.visitor.device.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 通行记录入库（docs/11 §1.2）。
 *
 * <p>两条硬约束：
 * <ol>
 *   <li><b>先落库、再返回 ok</b>。反过来一旦进程退出，设备认为已送达不会重推，记录永久丢失；</li>
 *   <li><b>照片不进 MySQL</b>。设备报文里的 {@code face_base64} 在落库前剥离，
 *       交给 {@link CaptureUploader} 异步上传对象存储（docs/10 §13）。</li>
 * </ol>
 */
@Service
public class AccessRecordService {

    private static final Logger log = LoggerFactory.getLogger(AccessRecordService.class);

    /**
     * @param duplicate   true 表示设备重推的同一条记录
     * @param eventId     首次入库的事件主键（照片关联用）
     */
    public record Outcome(boolean stored, boolean duplicate, boolean deviceKnown, Long eventId) {
    }

    private final AccessEventRepository repository;
    private final DeviceRepository deviceRepository;
    private final CaptureUploader captureUploader;
    private final VisitRecorder visitRecorder;
    private final ObjectMapper mapper;

    /** 单构造器：避免 Spring 多构造器注入歧义（曾导致启动失败）。 */
    public AccessRecordService(AccessEventRepository repository,
                               DeviceRepository deviceRepository,
                               CaptureUploader captureUploader,
                               VisitRecorder visitRecorder,
                               ObjectMapper mapper) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.captureUploader = captureUploader;
        this.visitRecorder = visitRecorder;
        this.mapper = mapper;
    }

    public Outcome handle(RecordPayload payload, String rawJson) {
        Device device = deviceRepository.findBySn(payload.getSn()).orElse(null);
        if (device == null) {
            log.warn("收到未登记设备的通行记录 SN={} employee={}", payload.getSn(), payload.getEmployeeNumber());
        }

        LocalDateTime eventTime = payload.eventTime();
        if (eventTime == null) {
            eventTime = LocalDateTime.now(ZoneId.systemDefault());
            log.warn("记录缺少有效时间，已用服务器时间兜底 SN={} id={}", payload.getSn(), payload.getId());
        }

        String eventUuid = payload.getId();
        if (eventUuid == null || eventUuid.isBlank()) {
            eventUuid = payload.getSn() + "-" + eventTime + "-" + payload.getEmployeeNumber();
            log.warn("记录缺少 id，使用合成幂等键 {}（建议向厂商确认固件版本）", eventUuid);
        }

        AccessEvent event = new AccessEvent(
                eventUuid,
                device == null ? null : device.getId(),
                payload.getSn(),
                device == null ? null : device.getGateId(),
                payload.directionOrDefault(),
                eventTime,
                payload.identifyTypeCode(),
                payload.getEmployeeNumber(),
                payload.getName(),
                maskIdCard(payload.getIdNum()),
                payload.resultStatusOrDefault(),
                payload.resultStatusOrDefault() == 1 ? null : "设备判定失败",
                null,
                payload.temperatureOrNull(),
                false,
                0,
                1,
                stripBinaryFields(rawJson));

        Optional<Long> inserted = repository.insertIfAbsent(event);
        if (inserted.isEmpty()) {
            log.debug("重复记录已忽略 SN={} id={}", payload.getSn(), eventUuid);
            return new Outcome(false, true, device != null, null);
        }

        Long eventId = inserted.get();
        // 照片异步上传：不能挡在 ack 之前；无照片时不做无谓调度
        if (payload.getFaceBase64() != null && !payload.getFaceBase64().isBlank()) {
            captureUploader.uploadAsync(eventId, payload.getSn(), eventUuid, payload.getFaceBase64());
        }
        // 签到/签离：失败只影响报表，不能影响通行与记录入库
        try {
            visitRecorder.record(event, eventId);
        } catch (Exception e) {
            log.error("签到/签离处理失败 eventUuid={} personNo={}", eventUuid, event.personNo(), e);
        }
        return new Outcome(true, false, device != null, eventId);
    }

    /**
     * 剥离报文里的二进制字段后再落库。
     *
     * <p>解析失败返回 null 而不是截断：{@code access_event.raw} 是 JSON 列，
     * 塞入非法 JSON 会直接报错，导致记录丢失。
     */
    private String stripBinaryFields(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(rawJson);
            if (node instanceof ObjectNode obj) {
                obj.remove("face_base64");
                obj.remove("templatePhoto");
                obj.remove("capture");
                return mapper.writeValueAsString(obj);
            }
            return null;
        } catch (Exception e) {
            log.warn("报文非合法 JSON，raw 置空以避免写入非法 JSON 列：{}", e.getMessage());
            return null;
        }
    }

    /** 设备上报的证件号可能已是脱敏值；这里只做保底处理，避免把完整证件号写进日志表。 */
    private String maskIdCard(String idNum) {
        if (idNum == null || idNum.isBlank()) {
            return null;
        }
        String value = idNum.trim();
        if (value.contains("*") || value.length() <= 10) {
            return value;
        }
        return value.substring(0, 6) + "********" + value.substring(value.length() - 4);
    }
}
