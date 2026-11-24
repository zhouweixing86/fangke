package com.fangke.visitor.appointment.service;

import com.fangke.visitor.appointment.dto.AppointmentCreateRequest;
import com.fangke.visitor.appointment.dto.AppointmentView;
import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.BlacklistRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.gate.repository.InMemoryGateRepository;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.appointment.repository.InMemoryAppointmentGateRepository;
import com.fangke.visitor.security.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppointmentServiceTest {

    private static final String VISITOR_ID = "110101199003074477";
    private static final String VISIT_PHONE = "13800138000";

    /* ---------------- 测试替身 ---------------- */

    static class InMemoryVisitorRepository implements VisitorRepository {
        final Map<Long, Visitor> store = new LinkedHashMap<>();
        final List<String> consents = new ArrayList<>();
        final AtomicLong seq = new AtomicLong();

        @Override
        public Optional<Visitor> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Visitor> findByIdCardHmac(String hmac) {
            return store.values().stream().filter(v -> hmac.equals(v.getIdCardHmac())).findFirst();
        }

        @Override
        public Optional<Visitor> findByPhoneHmac(String phoneHmac) {
            return store.values().stream().filter(v -> phoneHmac.equals(v.getPhoneHmac())).findFirst();
        }

        @Override
        public long insert(Visitor v) {
            long id = seq.incrementAndGet();
            v.setId(id);
            store.put(id, v);
            return id;
        }

        @Override
        public void update(Visitor v) {
            store.put(v.getId(), v);
        }

        @Override
        public Optional<Visitor> findByWxOpenid(String wxOpenid) {
            return Optional.empty();
        }

        @Override
        public void bindWxOpenid(Long visitorId, String wxOpenid) {
            // 单测不校验 openid 绑定
        }

        @Override
        public void updateFace(Long visitorId, String ossKey, int status) {
        }
        @Override
        public void recordConsent(Long visitorId, int consentType, String version, String ip, String ua) {
            consents.add(visitorId + ":" + consentType + ":" + version);
        }

        @Override
        public Optional<String> findMaxPersonNoByPrefix(String prefix) {
            return store.values().stream().map(Visitor::getPersonNo)
                    .filter(p -> p.startsWith(prefix)).max(String::compareTo);
        }
    }

    static class InMemoryAppointmentRepository implements AppointmentRepository {
        final Map<Long, Appointment> store = new LinkedHashMap<>();
        final List<AppointmentMate> mates = new ArrayList<>();
        final AtomicLong seq = new AtomicLong();

        @Override
        public long insert(Appointment a) {
            long id = seq.incrementAndGet();
            a.setId(id);
            a.setCreatedAt(LocalDateTime.now());
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
            return store.values().stream()
                    .filter(a -> a.getVisitorId().equals(visitorId))
                    .filter(a -> status == null || a.getStatus() == status)
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public long countByVisitor(Long visitorId, Integer status) {
            return store.values().stream()
                    .filter(a -> a.getVisitorId().equals(visitorId))
                    .filter(a -> status == null || a.getStatus() == status).count();
        }

        @Override
        public Optional<Appointment> findInProgress(Long visitorId) {
            return store.values().stream()
                    .filter(a -> a.getVisitorId().equals(visitorId))
                    .filter(a -> Appointment.isInProgress(a.getStatus()))
                    .findFirst();
        }

        @Override
        public void insertMate(AppointmentMate mate) {
            mates.add(mate);
        }

        @Override
        public List<AppointmentMate> findMates(Long appointmentId) {
            return mates.stream().filter(m -> m.appointmentId().equals(appointmentId)).toList();
        }

        @Override
        public Optional<String> findMaxNoByPrefix(String prefix) {
            return store.values().stream().map(Appointment::getAppointmentNo)
                    .filter(n -> n.startsWith(prefix)).max(String::compareTo);
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

    static class FakeBlacklist implements BlacklistRepository {
        final List<String> blocked = new ArrayList<>();

        @Override
        public boolean isBlacklisted(int targetType, String targetHmac, LocalDateTime at) {
            return blocked.contains(targetHmac);
        }
    }

    private InMemoryVisitorRepository visitorRepo;
    private InMemoryAppointmentRepository appointmentRepo;
    private FakeBlacklist blacklist;
    private CryptoService crypto;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        visitorRepo = new InMemoryVisitorRepository();
        appointmentRepo = new InMemoryAppointmentRepository();
        blacklist = new FakeBlacklist();

        AppProperties props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");
        crypto = new CryptoService(props);

        VisitorService visitorService = new VisitorService(visitorRepo, crypto);
        VisitEmployeeResolver resolver = hmac -> Optional.of(9L);
        GateService gateService = new GateService(InMemoryGateRepository.withUnassigned(), props);
        service = new AppointmentService(appointmentRepo, visitorRepo, visitorService, resolver,
                blacklist, crypto, ApprovalStarter.noop(), gateService,
                new AppointmentGateService(new InMemoryAppointmentGateRepository(), gateService),
                areaId -> areaId != null && areaId == 1L ? Optional.of("一号库房") : Optional.empty());
    }

    private AppointmentCreateRequest request(String visitorType, LocalDate date, Integer mateMode,
                                             List<AppointmentCreateRequest.MateInfo> mates) {
        return new AppointmentCreateRequest(
                VISIT_PHONE, "行政部", visitorType, "技术交流", "",
                date, LocalTime.of(9, 0), LocalTime.of(11, 0),
                new AppointmentCreateRequest.VisitorInfo("张三", VISITOR_ID, "13900139000", "某某科技"),
                mates, mateMode, "v1.0", null, null, null, null, null, null, null, null);
    }

    private AppointmentCreateRequest validRequest() {
        return request("商务", LocalDate.now().plusDays(1), 0, List.of());
    }

    /* ---------------- 用例 ---------------- */

    @Test
    @DisplayName("访客自约：单号规则、待审批状态、主访客落 mate、同意记录")
    void createSelfBooking() {
        AppointmentView view = service.create(validRequest(), Appointment.SOURCE_SELF, "127.0.0.1", "junit");

        // 单号规则：A + 提交日期 yyyyMMdd + 5 位序号（docs/01 §6.2）
        assertTrue(view.appointmentNo().startsWith("A" + LocalDate.now().toString().replace("-", "")),
                view.appointmentNo());
        assertEquals(Appointment.PENDING_L1, view.status());
        assertEquals("待审批", view.statusText());
        assertEquals("张三", view.visitorName());
        assertEquals("110101********4477", view.visitorIdCardMask());
        assertTrue(view.canCancel());
        assertEquals(9L, appointmentRepo.store.values().iterator().next().getVisitEmployeeId());
        assertEquals(1, visitorRepo.consents.size());
    }

    @Test
    @DisplayName("区域关联：预约带区域时返回区域名，落库 area_id")
    void createWithArea() {
        AppointmentCreateRequest withArea = new AppointmentCreateRequest(
                VISIT_PHONE, "行政部", "商务", "技术交流", "",
                LocalDate.now().plusDays(1), LocalTime.of(9, 0), LocalTime.of(11, 0),
                new AppointmentCreateRequest.VisitorInfo("李四", VISITOR_ID, "13900139001", "某某科技"),
                List.of(), 0, "v1.0", null, null, null, null, null, null, null, null, null, 1L);

        AppointmentView view = service.create(withArea, Appointment.SOURCE_SELF, "ip", "ua");

        assertEquals(1L, view.areaId());
        assertEquals("一号库房", view.areaName());
        assertEquals(1L, appointmentRepo.store.values().iterator().next().getAreaId());
    }

    @Test
    @DisplayName("区域关联：不存在的区域被拒（4001）")
    void invalidAreaRejected() {
        AppointmentCreateRequest badArea = new AppointmentCreateRequest(
                VISIT_PHONE, "行政部", "商务", "技术交流", "",
                LocalDate.now().plusDays(1), LocalTime.of(9, 0), LocalTime.of(11, 0),
                new AppointmentCreateRequest.VisitorInfo("李四", VISITOR_ID, "13900139001", "某某科技"),
                List.of(), 0, "v1.0", null, null, null, null, null, null, null, null, null, 99L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(badArea, Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("同一访客重复提交被拒（3002）")
    void duplicateInProgress() {
        service.create(validRequest(), Appointment.SOURCE_SELF, "ip", "ua");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(validRequest(), Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.APPOINTMENT_DUPLICATED.code(), e.getCode());
    }

    @Test
    @DisplayName("过去的日期被拒（3001）")
    void pastDate() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(
                request("商务", LocalDate.now().minusDays(1), 0, List.of()),
                Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.SLOT_EXPIRED.code(), e.getCode());
    }

    @Test
    @DisplayName("超过 30 天被拒")
    void tooFarAhead() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(
                request("商务", LocalDate.now().plusDays(31), 0, List.of()),
                Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("访客类型非法被拒（只允许商务/施工/送货）")
    void invalidVisitorType() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(
                request("旅游", LocalDate.now().plusDays(1), 0, List.of()),
                Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("结束时间早于开始时间被拒")
    void invalidTimeRange() {
        AppointmentCreateRequest bad = new AppointmentCreateRequest(
                VISIT_PHONE, null, "商务", null, null,
                LocalDate.now().plusDays(1), LocalTime.of(11, 0), LocalTime.of(9, 0),
                new AppointmentCreateRequest.VisitorInfo("张三", VISITOR_ID, null, null),
                List.of(), 0, "v1.0", null, null, null, null, null, null, null, null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(bad, Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("各自刷证模式下同行人必须带身份证（3013）")
    void mateIdCardRequired() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(
                request("商务", LocalDate.now().plusDays(1), Appointment.MATE_SEPARATE,
                        List.of(new AppointmentCreateRequest.MateInfo("李四", null, null))),
                Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.MATE_ID_CARD_REQUIRED.code(), e.getCode());
    }

    @Test
    @DisplayName("随行模式下同行人身份证同样必填（访客只能刷证进门）")
    void matesTogetherRequireIdCard() {
        AppointmentView view = service.create(
                request("商务", LocalDate.now().plusDays(1), Appointment.MATE_TOGETHER,
                        List.of(new AppointmentCreateRequest.MateInfo("李四", null, "340104199001011239"))),
                Appointment.SOURCE_SELF, "ip", "ua");

        assertEquals(1, view.mates().size());
        assertEquals("李四", view.mates().get(0).name());
        assertEquals(2, appointmentRepo.mates.size(), "主访客 + 1 同行人");
        assertTrue(appointmentRepo.mates.stream().anyMatch(AppointmentMate::main));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(
                        request("商务", LocalDate.now().plusDays(1), Appointment.MATE_TOGETHER,
                                List.of(new AppointmentCreateRequest.MateInfo("王五", null, null))),
                        Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.MATE_ID_CARD_REQUIRED.code(), e.getCode());
    }

    @Test
    @DisplayName("命中黑名单被拒（3003）")
    void blacklisted() {
        blacklist.blocked.add(crypto.hmac(VISITOR_ID));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(validRequest(), Appointment.SOURCE_SELF, "ip", "ua"));
        assertEquals(ErrorCode.BLACKLISTED.code(), e.getCode());
    }

    @Test
    @DisplayName("员工代邀：一级自动通过（status=30），超过 7 天被拒")
    void invite() {
        AppointmentView view = service.createInvite(
                request("商务", LocalDate.now().plusDays(2), 0, List.of()), 5L, "ip", "ua");
        assertEquals(Appointment.APPROVED, view.status());
        assertFalse(view.canCancel(), "已通过的单不能撤回");

        BusinessException e = assertThrows(BusinessException.class, () -> service.createInvite(
                request("商务", LocalDate.now().plusDays(8), 0, List.of()),
                new java.util.Random().nextLong() + 100L, "ip", "ua"));
        assertEquals(ErrorCode.INVITE_LIMIT_EXCEEDED.code(), e.getCode());
    }

    @Test
    @DisplayName("撤回：待审批可撤回，已通过不可撤回")
    void cancel() {
        AppointmentView view = service.create(validRequest(), Appointment.SOURCE_SELF, "ip", "ua");
        service.cancel(view.appointmentNo(), "临时有事");

        Appointment cancelled = appointmentRepo.findByNo(view.appointmentNo()).orElseThrow();
        assertEquals(Appointment.WITHDRAWN, cancelled.getStatus());
        assertEquals("临时有事", cancelled.getCancelReason());

        Appointment approved = appointmentRepo.store.values().iterator().next();
        approved.setStatus(Appointment.GRANTED);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.cancel(view.appointmentNo(), "再撤一次"));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), e.getCode());
    }

    @Test
    @DisplayName("现场登记：日期固定今天，时段默认现在到 23:59")
    void onsiteDefaults() {
        AppointmentCreateRequest noTime = new AppointmentCreateRequest(
                VISIT_PHONE, null, "施工", null, null,
                null, null, null,
                new AppointmentCreateRequest.VisitorInfo("王五", VISITOR_ID, null, null),
                List.of(), 0, "v1.0", null, null, null, null, null, null, null, null);

        AppointmentView view = service.createOnsite(noTime, "ip", "ua");

        assertEquals(LocalDate.now().toString(), view.visitDate());
        assertTrue(view.startTime().endsWith(":")
                || view.startTime().contains(" "), view.startTime());
        assertEquals(Appointment.SOURCE_ONSITE,
                appointmentRepo.store.values().iterator().next().getSource());
    }

    @Test
    @DisplayName("访客档案按身份证号复用，不重复建档")
    void visitorReused() {
        AppointmentView first = service.create(validRequest(), Appointment.SOURCE_SELF, "ip", "ua");
        appointmentRepo.store.values().iterator().next().setStatus(Appointment.CHECKED_OUT);

        AppointmentView second = service.create(validRequest(), Appointment.SOURCE_SELF, "ip", "ua");

        assertEquals(1, visitorRepo.store.size(), "同一身份证只应有一个访客档案");
        assertNotNull(second.appointmentNo());
        assertTrue(!first.appointmentNo().equals(second.appointmentNo()));
    }
}
