package com.fangke.visitor.guard.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.approval.service.ReminderPort;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.model.AccessEvent;
import com.fangke.visitor.device.service.AccessEventRepository;
import com.fangke.visitor.device.service.GrantChecker;
import com.fangke.visitor.device.service.VisitRecordRepository;
import com.fangke.visitor.device.service.VisitRecordService;
import com.fangke.visitor.guard.repository.GuardQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardServiceTest {

    /* ---------------- 测试替身 ---------------- */

    static class FakeQueryRepo implements GuardQueryRepository {
        Counts counts = new Counts(2, 3, 1, 2, 5);
        List<OnsiteRow> onsite = new ArrayList<>();
        List<PendingRow> pending = new ArrayList<>();

        @Override
        public Counts counts() {
            return counts;
        }

        @Override
        public List<OnsiteRow> onsite(int offset, int limit) {
            return onsite;
        }

        @Override
        public long countOnsite() {
            return onsite.size();
        }

        @Override
        public List<PendingRow> pending(int offset, int limit) {
            return pending;
        }

        @Override
        public long countPending() {
            return pending.size();
        }
    }

    static class FakeAppointmentRepo implements AppointmentRepository {
        final Map<Long, Appointment> store = new LinkedHashMap<>();

        @Override
        public long insert(Appointment a) {
            long id = store.size() + 1;
            a.setId(id);
            store.put(id, a);
            return id;
        }

        @Override
        public void update(Appointment a) {
            store.put(a.getId(), a);
        }

        @Override
        public void updateStatus(Long id, int status) {
            store.get(id).setStatus(status);
        }

        @Override
        public Optional<Appointment> findByNo(String appointmentNo) {
            return store.values().stream().filter(a -> a.getAppointmentNo().equals(appointmentNo)).findFirst();
        }

        @Override
        public Optional<Appointment> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Appointment> findByVisitor(Long visitorId, Integer status, int offset, int limit) {
            return List.of();
        }

        @Override
        public long countByVisitor(Long visitorId, Integer status) {
            return 0;
        }

        @Override
        public Optional<Appointment> findInProgress(Long visitorId) {
            return Optional.empty();
        }

        @Override
        public void insertMate(AppointmentMate mate) {
        }

        @Override
        public List<AppointmentMate> findMates(Long appointmentId) {
            return List.of();
        }

        @Override
        public Optional<String> findMaxNoByPrefix(String prefix) {
            return Optional.empty();
        }

        @Override
        public java.util.List<Long> findExpirableIds(java.time.LocalDateTime before, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.fangke.visitor.appointment.model.Appointment> findActiveByVisitor(Long visitorId) {
            return java.util.List.of();
        }
    }

    static class FakeEventRepo implements AccessEventRepository {
        final List<AccessEvent> events = new ArrayList<>();

        @Override
        public Optional<Long> insertIfAbsent(AccessEvent event) {
            events.add(event);
            return Optional.of((long) events.size());
        }

        @Override
        public Optional<Long> findPlatformQrEvent(String deviceSn, Long gateId, java.time.LocalDateTime eventTime) {
            return Optional.empty();
        }
    }

    static class FakeVisitRepo implements VisitRecordRepository {
        Long openRecordId;
        Integer checkoutStatus;
        Integer appointmentStatus;
        Long checkedOutRecord;

        @Override
        public Optional<Long> findAppointmentIdByPersonNo(String personNo) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> findOpenRecordId(Long appointmentId) {
            return Optional.ofNullable(openRecordId);
        }

        @Override
        public Optional<Long> findAnyRecordId(Long appointmentId) {
            return Optional.ofNullable(openRecordId);
        }

        @Override
        public long insertCheckin(Long appointmentId, Long visitorId, Long gateId,
                                  LocalDateTime checkinAt, Long checkinEvent, LocalDate visitDate) {
            return 1L;
        }

        @Override
        public void updateCheckout(long recordId, LocalDateTime checkoutAt, Long checkoutEvent,
                                   int checkoutType, int status) {
            checkedOutRecord = recordId;
            checkoutStatus = status;
        }

        @Override
        public void updateAppointmentStatus(Long appointmentId, int status) {
            appointmentStatus = status;
        }

        @Override
        public java.util.List<OverdueRecord> findOverdueOpen(java.time.LocalDateTime before, int limit) {
            return java.util.List.of();
        }
    }

    static class FakeDoor implements DoorOpener {
        int opened = 1;
        boolean fail;
        final List<Long> gates = new ArrayList<>();

        @Override
        public int open(Long gateId) {
            if (fail) {
                throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "模拟设备离线");
            }
            gates.add(gateId);
            return opened;
        }
    }

    /* ---------------- 固定数据 ---------------- */

    private FakeQueryRepo queryRepo;
    private FakeAppointmentRepo appointmentRepo;
    private FakeEventRepo eventRepo;
    private FakeVisitRepo visitRepo;
    private FakeDoor door;
    private GrantChecker.State grantState;
    private final List<Long> reminded = new ArrayList<>();
    private GuardService service;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        queryRepo = new FakeQueryRepo();
        appointmentRepo = new FakeAppointmentRepo();
        eventRepo = new FakeEventRepo();
        visitRepo = new FakeVisitRepo();
        door = new FakeDoor();
        grantState = GrantChecker.State.OK;

        appointment = new Appointment();
        appointment.setAppointmentNo("A2026111600001");
        appointment.setVisitorId(1L);
        appointment.setStartTime(LocalDateTime.of(LocalDate.now(), LocalTime.of(9, 0)));
        appointment.setEndTime(LocalDateTime.of(LocalDate.now(), LocalTime.of(11, 0)));
        appointment.setStatus(Appointment.GRANTED);
        appointmentRepo.insert(appointment);

        ReminderPort reminder = (appointmentId, operatorId) -> {
            reminded.add(appointmentId);
            return 1;
        };
        service = new GuardService(queryRepo, appointmentRepo, eventRepo,
                (no, gateId, now) -> grantState, door,
                new VisitRecordService(visitRepo), reminder, new AppProperties());
    }

    /* ---------------- 用例 ---------------- */

    @Test
    @DisplayName("看板：在访/待审/设备在线状态文案")
    void dashboard() {
        GuardService.DashboardView view = service.dashboard();

        assertEquals(2, view.onsite());
        assertEquals(3, view.pendingApproval());
        assertEquals("1/2 在线", view.deviceStatusText());

        queryRepo.counts = new GuardQueryRepository.Counts(0, 0, 0, 0, 0);
        assertEquals("无设备", service.dashboard().deviceStatusText());
    }

    @Test
    @DisplayName("在访列表：超过预约结束时间标记超时")
    void onsiteOverdue() {
        queryRepo.onsite = List.of(
                new GuardQueryRepository.OnsiteRow(1L, "A2026111600001", "张三", "110101********1234",
                        "洽谈", LocalDateTime.now().minusHours(3), LocalDateTime.now().minusHours(1)),
                new GuardQueryRepository.OnsiteRow(2L, "A2026111600002", "李四", "110101********5678",
                        "面试", LocalDateTime.now(), LocalDateTime.now().plusHours(1)));

        PageResult<GuardService.OnsiteView> page = service.onsite(1, 20);

        assertEquals(2, page.getTotal());
        assertTrue(page.getList().get(0).overdue());
        assertFalse(page.getList().get(1).overdue());
    }

    @Test
    @DisplayName("待审批列表：来源文案与脱敏")
    void pendingSourceText() {
        queryRepo.pending = List.of(new GuardQueryRepository.PendingRow(1L, "A2026111600001", "王五",
                "110101********9999", "送货", LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                Appointment.SOURCE_ONSITE, LocalDateTime.now()));

        GuardService.PendingView view = service.pending(1, 20).getList().get(0);

        assertEquals("现场自助登记", view.sourceText());
        assertEquals("110101********9999", view.idCardMask());
    }

    @Test
    @DisplayName("催办：转交审批模块处理（同一套限流）")
    void remind() {
        int remaining = service.remind("A2026111600001", 9L);

        assertEquals(1, remaining);
        assertEquals(List.of(appointment.getId()), reminded);
    }

    @Test
    @DisplayName("访客开门：已授权且在有效期内才允许")
    void openDoorForVisitor() {
        int opened = service.openDoor(new GuardService.OpenDoorRequest(
                "A2026111600001", null, null, "二维码识别不了"), 9L);

        assertEquals(1, opened);
        assertEquals(List.of(1L), door.gates);
        assertEquals(1, eventRepo.events.size());
        assertEquals(3, eventRepo.events.get(0).source(), "来源应为手工补录");
    }

    @Test
    @DisplayName("访客未授权时拒绝开门（3004）")
    void openDoorRejectedWhenNotGranted() {
        appointment.setStatus(Appointment.PENDING_L1);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.openDoor(new GuardService.OpenDoorRequest(
                        "A2026111600001", null, null, "试试"), 9L));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), e.getCode());
        assertTrue(door.gates.isEmpty());
    }

    @Test
    @DisplayName("授权校验不通过（过期/门点不符）时拒绝开门（3010）")
    void openDoorRejectedOutOfWindow() {
        grantState = GrantChecker.State.OUT_OF_WINDOW;

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.openDoor(new GuardService.OpenDoorRequest(
                        "A2026111600001", null, null, "过期了"), 9L));
        assertEquals(ErrorCode.NOT_IN_AUTHORIZED_WINDOW.code(), e.getCode());
    }

    @Test
    @DisplayName("员工代开门：必须填原因")
    void employeeOpenRequiresReason() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.openDoor(new GuardService.OpenDoorRequest(null, 5L, null, " "), 9L));
        assertEquals(ErrorCode.PARAM_MISSING.code(), e.getCode());

        int opened = service.openDoor(new GuardService.OpenDoorRequest(
                null, 5L, null, "工牌核验：信息部张三"), 9L);
        assertEquals(1, opened);
    }

    @Test
    @DisplayName("设备全部失败时错误上抛，不误报成功")
    void doorFailure() {
        door.fail = true;
        assertThrows(DeviceException.class, () -> service.openDoor(
                new GuardService.OpenDoorRequest("A2026111600001", null, null, "x"), 9L));
    }

    @Test
    @DisplayName("手工签离：不在访时提示（3015）")
    void manualCheckout() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.manualCheckout("A2026111600001", 9L));
        assertEquals(ErrorCode.VISITOR_NOT_IN_SITE.code(), e.getCode());

        visitRepo.openRecordId = 77L;
        service.manualCheckout("A2026111600001", 9L);

        assertEquals(77L, visitRepo.checkedOutRecord);
        assertEquals(2, visitRepo.checkoutStatus, "手工签离应记为已离场");
        assertEquals(Appointment.CHECKED_OUT, visitRepo.appointmentStatus);
    }
}
