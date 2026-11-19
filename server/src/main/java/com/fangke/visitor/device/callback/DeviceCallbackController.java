package com.fangke.visitor.device.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.device.callback.dto.ComparePayload;
import com.fangke.visitor.device.callback.dto.CompareResponse;
import com.fangke.visitor.device.callback.dto.DeviceAck;
import com.fangke.visitor.device.callback.dto.HeartbeatPayload;
import com.fangke.visitor.device.callback.dto.RecordPayload;
import com.fangke.visitor.device.service.AccessRecordService;
import com.fangke.visitor.device.service.HeartbeatService;
import com.fangke.visitor.device.service.OnlineCompareService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备回调接口（设备 → 平台），见 docs/11 §1。
 *
 * <p><b>本类的返回体必须严格遵循厂商格式</b>，不能套 {@code ApiResponse}：
 * <ul>
 *   <li>心跳 / 记录：{@code {"message":"ok","result":0}}；</li>
 *   <li>在线比对：{@code {"status":0,"msg":"...","audio":"..."}}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/device/callback")
public class DeviceCallbackController {

    private static final Logger log = LoggerFactory.getLogger(DeviceCallbackController.class);

    private final ObjectMapper mapper;
    private final DeviceCallbackGuard guard;
    private final HeartbeatService heartbeatService;
    private final AccessRecordService accessRecordService;
    private final OnlineCompareService onlineCompareService;

    public DeviceCallbackController(ObjectMapper mapper,
                                    DeviceCallbackGuard guard,
                                    HeartbeatService heartbeatService,
                                    AccessRecordService accessRecordService,
                                    OnlineCompareService onlineCompareService) {
        this.mapper = mapper;
        this.guard = guard;
        this.heartbeatService = heartbeatService;
        this.accessRecordService = accessRecordService;
        this.onlineCompareService = onlineCompareService;
    }

    /**
     * 心跳：无论内部是否出错都回 ok，避免设备反复重试。
     */
    @PostMapping({"/heartbeat", "/heartBeat"})
    public DeviceAck heartbeat(@RequestBody(required = false) String raw, HttpServletRequest request) {
        if (!guard.allowed(request)) {
            return DeviceAck.ok();
        }
        try {
            HeartbeatPayload payload = mapper.readValue(raw == null ? "{}" : raw, HeartbeatPayload.class);
            heartbeatService.handle(payload, raw);
        } catch (Exception e) {
            log.error("心跳处理失败 ip={} body={}", guard.clientIp(request), raw, e);
        }
        return DeviceAck.ok();
    }

    /**
     * 通行记录：先落库再回 ok。落库失败时返回 result=1，让设备重推（宁可重复，不可丢失）。
     */
    @PostMapping({"/record", "/getrecord", "/getrecord/"})
    public ResponseEntity<DeviceAck> record(@RequestBody(required = false) String raw, HttpServletRequest request) {
        if (!guard.allowed(request)) {
            return ResponseEntity.ok(DeviceAck.ok());
        }
        try {
            RecordPayload payload = mapper.readValue(raw == null ? "{}" : raw, RecordPayload.class);
            AccessRecordService.Outcome outcome = accessRecordService.handle(payload, raw);
            log.debug("记录入库 SN={} id={} duplicate={} deviceKnown={}",
                    payload.getSn(), payload.getId(), outcome.duplicate(), outcome.deviceKnown());
            return ResponseEntity.ok(DeviceAck.ok());
        } catch (Exception e) {
            log.error("通行记录入库失败，提示设备重推 ip={} body={}", guard.clientIp(request), raw, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new DeviceAck("error", 1));
        }
    }

    /** 在线比对（动态二维码 / 人证）。 */
    @PostMapping({"/compare/online", "/qrcode"})
    public CompareResponse compare(@RequestBody(required = false) String raw, HttpServletRequest request) {
        if (!guard.allowed(request)) {
            return CompareResponse.reject(CompareResponse.NO_GRANT, "来源不允许");
        }
        try {
            ComparePayload payload = mapper.readValue(raw == null ? "{}" : raw, ComparePayload.class);
            return onlineCompareService.compare(payload);
        } catch (Exception e) {
            log.error("在线比对处理失败 ip={} body={}", guard.clientIp(request), raw, e);
            // 出错时不开门，但给出可见原因，便于现场判断
            return CompareResponse.reject(CompareResponse.NO_GRANT, "系统异常，请稍候");
        }
    }

    /** 指令回执（云通道用，P5 接入）。 */
    @PostMapping("/notify")
    public Map<String, Object> notify(@RequestBody(required = false) String raw) {
        log.info("收到设备指令回执: {}", raw);
        return Map.of("code", 0);
    }
}
