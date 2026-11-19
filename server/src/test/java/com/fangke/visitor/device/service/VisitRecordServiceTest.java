package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.AccessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitRecordServiceTest {

    /** 内存版到访记录仓储，模拟唯一键与状态更新。 */
    static class FakeRepo implements VisitRecordRepository {
        final Map<String, Long> appointmentByPerson = new HashMap<>();
        final List<Record> records = new ArrayList<>();
        final Map<Long, Integer> appointmentStatus = new HashMap<>();
        long seq = 0;

        static class Record {
            long id;
            Long appointmentId;
            LocalDateTime checkinAt;
            LocalDateTime checkoutAt;
            Long checkinEvent;
            Long checkoutEvent;
            Integer checkoutType;
            int status;
        }

        @Override
        public Optional<Long> findAppointmentIdByPersonNo(String personNo) {
            return Optional.ofNullable(appointmentByPerson.get(personNo));
        }

        @Override
        public Optional<Long> findOpenRecordId(Long appointmentId) {
            return records.stream()
                    .filter(r -> r.appointmentId.equals(appointmentId) && r.checkoutAt == null)
                    .map(r -> r.id).findFirst();
        }

        @Override
        public Optional<Long> findAnyRecordId(Long appointmentId) {
            return records.stream()
                    .filter(r -> r.appointmentId.equals(appointmentId))
                    .map(r -> r.id).findFirst();
        }

        @Override
        public long insertCheckin(Long appointmentId, Long visitorId, Long gateId,
                                  LocalDateTime checkinAt, Long checkinEvent, LocalDate visitDate) {
            Record r = new Record();
            r.id = ++seq;
            r.appointmentId = appointmentId;
            r.checkinAt = checkinAt;
            r.checkinEvent = checkinEvent;
            r.status = 1;
            records.add(r);
            return r.id;
        }

        @Override
        public void updateCheckout(long recordId, LocalDateTime checkoutAt, Long checkoutEvent,
                                   int checkoutType, int status) {
            records.stream().filter(r -> r.id == recordId).findFirst().ifPresent(r -> {
                r.checkoutAt = checkoutAt;
                r.checkoutEvent = checkoutEvent;
                r.checkoutType = checkoutType;
                r.status = status;
            });
        }

        @Override
        public void updateAppointmentStatus(Long appointmentId, int status) {
            appointmentStatus.put(appointmentId, status);
        }

        @Override
        public java.util.List<OverdueRecord> findOverdueOpen(java.time.LocalDateTime before, int limit) {
            return java.util.List.of();
        }
    }

    private FakeRepo repo;
    private VisitRecordService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        repo.appointmentByPerson.put("V0001", 100L);
        service = new VisitRecordService(repo);
    }

    private AccessEvent event(int direction, boolean success) {
        return new AccessEvent("evt-" + direction, 1L, "T77R781116ZS", 1L, direction,
                LocalDateTime.of(2026, 11, 16, 9, 30), 2, "V0001", "张三", null,
                success ? 1 : 0, success ? null : "设备判定失败", null, null, false, 0, 1, null);
    }

    @Test
    @DisplayName("入口识别 → 生成签到记录并把预约单置为已签到")
    void checkinOnEntry() {
        service.record(event(1, true), 900L);

        assertEquals(1, repo.records.size());
        FakeRepo.Record r = repo.records.get(0);
        assertNotNull(r.checkinAt);
        assertEquals(900L, r.checkinEvent);
        assertEquals(40, repo.appointmentStatus.get(100L));
    }

    @Test
    @DisplayName("重复进场不重复签到")
    void duplicateEntryIgnored() {
        service.record(event(1, true), 900L);
        service.record(event(1, true), 901L);

        assertEquals(1, repo.records.size());
    }

    @Test
    @DisplayName("出口识别 → 签离并置为已签离")
    void checkoutOnExit() {
        service.record(event(1, true), 900L);
        service.record(event(0, true), 902L);

        FakeRepo.Record r = repo.records.get(0);
        assertNotNull(r.checkoutAt);
        assertEquals(902L, r.checkoutEvent);
        assertEquals(1, r.checkoutType);
        assertEquals(2, r.status);
        assertEquals(50, repo.appointmentStatus.get(100L));
    }

    @Test
    @DisplayName("未签到就离场：不生成签离，但要有日志（不阻断人员）")
    void checkoutWithoutCheckinIgnored() {
        service.record(event(0, true), 902L);

        assertTrue(repo.records.isEmpty());
        assertTrue(repo.appointmentStatus.isEmpty());
    }

    @Test
    @DisplayName("核验失败的事件不产生签到签离")
    void failedEventIgnored() {
        service.record(event(1, false), 900L);

        assertTrue(repo.records.isEmpty());
    }

    @Test
    @DisplayName("无关联预约单（如员工）跳过签到签离")
    void unknownPersonIgnored() {
        AccessEvent employee = new AccessEvent("evt-e", 1L, "T77R781116ZS", 1L, 1,
                LocalDateTime.of(2026, 11, 16, 9, 30), 0, "E000123", "王五", null,
                1, null, null, null, false, 0, 1, null);

        service.record(employee, 901L);

        assertTrue(repo.records.isEmpty());
    }

    @Test
    @DisplayName("异常离场标记为异常状态")
    void abnormalCheckout() {
        service.record(event(1, true), 900L);
        AccessEvent abnormalExit = new AccessEvent("evt-x", 1L, "T77R781116ZS", 1L, 0,
                LocalDateTime.of(2026, 11, 16, 18, 0), 2, "V0001", "张三", null,
                1, null, null, null, false, 1, 1, null);

        service.record(abnormalExit, 903L);

        assertEquals(3, repo.records.get(0).status);
    }
}
