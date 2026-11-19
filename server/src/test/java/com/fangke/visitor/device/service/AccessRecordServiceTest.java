package com.fangke.visitor.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.device.callback.dto.RecordPayload;
import com.fangke.visitor.device.model.AccessEvent;
import com.fangke.visitor.device.model.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRecordServiceTest {

    /** 记录照片上传调用，用于断言“照片离开 MySQL、交给对象存储”。 */
    record Upload(Long eventId, String deviceSn, String eventUuid, String base64) {
    }

    private InMemoryDeviceRepository deviceRepository;
    private InMemoryAccessEventRepository eventRepository;
    private List<Upload> uploads;
    private AccessRecordService service;

    @BeforeEach
    void setUp() {
        deviceRepository = new InMemoryDeviceRepository();
        Device device = new Device();
        device.setId(1L);
        device.setDeviceSn("T77R781116ZS");
        device.setMac("0A:0C:E8:68:11:16");
        device.setGateId(1L);
        deviceRepository.put(device);

        eventRepository = new InMemoryAccessEventRepository();
        uploads = new ArrayList<>();

        CaptureUploader uploader = (eventId, deviceSn, eventUuid, base64) ->
                uploads.add(new Upload(eventId, deviceSn, eventUuid, base64));

        service = new AccessRecordService(eventRepository, deviceRepository, uploader, VisitRecorder.noop(), new ObjectMapper());
    }

    private RecordPayload payload(String id) {
        RecordPayload p = new RecordPayload();
        p.setId(id);
        p.setSn("T77R781116ZS");
        p.setMacAddr("0A:0C:E8:68:11:16");
        p.setTime("2026-11-16 09:47:31");
        p.setInout(1);
        p.setEmployeeNumber("V202611160001");
        p.setName("张三");
        p.setIdentifyType("2");
        p.setResultStatus(1);
        p.setIdNum("110101199001011234");
        return p;
    }

    private String rawWithPhoto() {
        return "{\"id\":\"evt-1\",\"SN\":\"T77R781116ZS\",\"employee_number\":\"V0001\","
                + "\"face_base64\":\"/9j/4AAQSkZJRgABAQ==\",\"temperature\":\"0.00\"}";
    }

    @Test
    @DisplayName("首次记录入库成功，方向与时间按设备值")
    void storesEvent() {
        AccessRecordService.Outcome outcome = service.handle(payload("evt-1"), "{}");

        assertTrue(outcome.stored());
        assertFalse(outcome.duplicate());
        assertTrue(outcome.deviceKnown());
        assertNotNull(outcome.eventId());

        AccessEvent event = eventRepository.events().get(0);
        assertEquals(1, event.direction());
        assertEquals(2, event.identifyType());
        assertEquals(1, event.resultStatus());
        assertEquals(1L, event.gateId());
        assertEquals("V202611160001", event.personNo());
    }

    @Test
    @DisplayName("设备重推同一条记录：第二次判定为重复，不重复入库也不重复上传照片")
    void duplicateIsIdempotent() {
        service.handle(payload("evt-1"), "{}");
        AccessRecordService.Outcome second = service.handle(payload("evt-1"), "{}");

        assertFalse(second.stored());
        assertTrue(second.duplicate());
        assertEquals(1, eventRepository.events().size());
        assertEquals(0, uploads.size(), "重复记录不应再次上传照片");
    }

    @Test
    @DisplayName("照片剥离：base64 不进 MySQL，转交对象存储")
    void photoIsStrippedAndUploaded() {
        RecordPayload p = payload("evt-photo");
        p.setFaceBase64("/9j/4AAQSkZJRgABAQ==");

        service.handle(p, rawWithPhoto());

        AccessEvent event = eventRepository.events().get(0);
        assertNotNull(event.rawJson());
        assertFalse(event.rawJson().contains("face_base64"), "raw 里不能再有 base64：" + event.rawJson());
        assertFalse(event.rawJson().contains("/9j/4AAQ"), "raw 里不能再有图片数据");
        assertTrue(event.rawJson().contains("employee_number"), "其余字段要保留便于排障");

        assertEquals(1, uploads.size());
        assertEquals("/9j/4AAQSkZJRgABAQ==", uploads.get(0).base64());
        assertEquals(eventRepository.events().size(), 1);
        assertEquals(uploads.get(0).eventId(), 1L);
    }

    @Test
    @DisplayName("raw 非合法 JSON 时置空，避免写入 JSON 列失败导致丢记录")
    void invalidRawBecomesNull() {
        service.handle(payload("evt-bad-raw"), "not-a-json");

        assertNull(eventRepository.events().get(0).rawJson());
        assertTrue(eventRepository.events().size() == 1);
    }

    @Test
    @DisplayName("身份证号落库前脱敏，不写完整证件号")
    void idCardIsMasked() {
        service.handle(payload("evt-2"), "{}");
        assertEquals("110101********1234", eventRepository.events().get(0).idCardMask());
    }

    @Test
    @DisplayName("未登记设备仍入库，便于排查，但标记 deviceKnown=false")
    void unknownDeviceStillStored() {
        RecordPayload p = payload("evt-3");
        p.setSn("UNKNOWN");
        AccessRecordService.Outcome outcome = service.handle(p, "{}");

        assertTrue(outcome.stored());
        assertFalse(outcome.deviceKnown());
    }

    @Test
    @DisplayName("缺失 id 时用合成键兜底，仍能入库")
    void missingIdFallsBack() {
        AccessRecordService.Outcome outcome = service.handle(payload(null), "{}");

        assertTrue(outcome.stored());
        assertTrue(eventRepository.events().get(0).eventUuid().startsWith("T77R781116ZS-"));
    }
}
