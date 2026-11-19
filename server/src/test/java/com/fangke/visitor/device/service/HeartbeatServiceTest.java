package com.fangke.visitor.device.service;

import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.callback.dto.HeartbeatPayload;
import com.fangke.visitor.device.model.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatServiceTest {

    private InMemoryDeviceRepository repository;
    private HeartbeatService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDeviceRepository();
        Device device = new Device();
        device.setId(1L);
        device.setDeviceSn("T77R781116ZS");
        device.setMac("0A:0C:E8:68:11:16");
        device.setIp("192.168.10.135");
        device.setGateId(1L);
        device.setCapacity(500);
        device.setApiPassword("123456");
        repository.put(device);
        service = new HeartbeatService(repository, new AppProperties(), DeviceOnlineListener.noop());
    }

    private HeartbeatPayload payload(String password, int personCount, long epochSeconds) {
        HeartbeatPayload p = new HeartbeatPayload();
        p.setSn("T77R781116ZS");
        p.setIp("192.168.10.135");
        p.setPassword(password);
        p.setPersonCount(personCount);
        p.setCurrentTime(String.valueOf(epochSeconds));
        return p;
    }

    @Test
    @DisplayName("正常心跳：置在线并落样本")
    void updatesOnlineState() {
        long now = Instant.now().getEpochSecond();
        HeartbeatService.Outcome outcome = service.handle(payload("123456", 3, now), "{}");

        assertTrue(outcome.deviceKnown());
        assertTrue(outcome.passwordOk());
        assertTrue(repository.findBySn("T77R781116ZS").orElseThrow().isOnline());
        assertEquals(1, repository.heartbeatLog().size());
    }

    @Test
    @DisplayName("口令不匹配：不影响应答，但标记 passwordOk=false")
    void wrongPasswordStillHandled() {
        long now = Instant.now().getEpochSecond();
        HeartbeatService.Outcome outcome = service.handle(payload("000000", 3, now), "{}");

        assertTrue(outcome.deviceKnown());
        assertFalse(outcome.passwordOk());
        assertTrue(repository.findBySn("T77R781116ZS").orElseThrow().isOnline());
    }

    @Test
    @DisplayName("未登记设备：返回 deviceKnown=false，不写库")
    void unknownDevice() {
        HeartbeatPayload p = payload("123456", 1, Instant.now().getEpochSecond());
        p.setSn("UNKNOWN-SN");
        HeartbeatService.Outcome outcome = service.handle(p, "{}");

        assertFalse(outcome.deviceKnown());
        assertTrue(repository.heartbeatLog().isEmpty());
    }

    @Test
    @DisplayName("人数超过容量 80% 触发容量告警")
    void capacityAlert() {
        long now = Instant.now().getEpochSecond();
        HeartbeatService.Outcome outcome = service.handle(payload("123456", 450, now), "{}");
        assertTrue(outcome.capacityAlert());
    }

    @Test
    @DisplayName("设备时钟偏差被计算出来")
    void clockSkewCalculated() {
        long fiveMinutesAgo = Instant.now().getEpochSecond() - 300;
        HeartbeatService.Outcome outcome = service.handle(payload("123456", 1, fiveMinutesAgo), "{}");

        assertTrue(outcome.clockSkewSeconds() >= 300);
    }
}
