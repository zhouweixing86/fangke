package com.fangke.visitor.dispatch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.InMemoryAppointmentGateRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.AppointmentGateService;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.PersonPayload;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.device.service.InMemoryDeviceRepository;
import com.fangke.visitor.device.service.PersonStateStore;
import com.fangke.visitor.dispatch.model.AccessGrant;
import com.fangke.visitor.dispatch.model.SyncBatch;
import com.fangke.visitor.dispatch.model.SyncBatchItem;
import com.fangke.visitor.dispatch.repository.AccessGrantRepository;
import com.fangke.visitor.dispatch.repository.SyncBatchRepository;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.repository.InMemoryGateRepository;
import com.fangke.visitor.gate.service.GateService;
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

class DispatchServiceTest {

    private static final String ID_CARD = "110101199003074477";

    /* ---------------- 测试替身 ---------------- */

    static class FakeGrantRepo implements AccessGrantRepository {
        final Map<Long, AccessGrant> store = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong();

        @Override
        public Optional<AccessGrant> findByAppointmentId(Long appointmentId) {
            return store.values().stream().filter(g -> appointmentId.equals(g.appointmentId())).findFirst();
        }

        @Override
        public Optional<AccessGrant> findByAppointmentIdAndGate(Long appointmentId, Long gateId) {
            return store.values().stream()
                    .filter(g -> appointmentId.equals(g.appointmentId()) && gateId.equals(g.gateId()))
                    .findFirst();
        }

        @Override
        public java.util.List<AccessGrant> findAllByAppointmentId(Long appointmentId) {
            return store.values().stream().filter(g -> appointmentId.equals(g.appointmentId())).toList();
        }

        @Override
        public java.util.List<AccessGrant> findBySubject(int subjectType, Long subjectId) {
            return store.values().stream()
                    .filter(g -> g.subjectType() == subjectType && subjectId.equals(g.subjectId()))
                    .toList();
        }

        @Override
        public Optional<AccessGrant> findBySubjectAndGate(int subjectType, Long subjectId, Long gateId) {
            return store.values().stream()
                    .filter(g -> g.subjectType() == subjectType && subjectId.equals(g.subjectId())
                            && gateId.equals(g.gateId()))
                    .findFirst();
        }

        @Override
        public Optional<AccessGrant> findLatestByPersonNo(String personNo) {
            return store.values().stream().filter(g -> personNo.equals(g.personNo()))
                    .reduce((a, b) -> b);
        }

        @Override
        public java.util.List<AccessGrant> search(Integer subjectType, Integer status, int offset, int limit) {
            return store.values().stream().skip(offset).limit(limit).toList();
        }

        @Override
        public long count(Integer subjectType, Integer status) {
            return store.size();
        }

        @Override
        public long insert(AccessGrant g) {
            long id = seq.incrementAndGet();
            store.put(id, new AccessGrant(id, g.subjectType(), g.subjectId(), g.personNo(), g.appointmentId(),
                    g.gateId(), g.validFrom(), g.validTo(), g.status()));
            return id;
        }

        @Override
        public void updateStatus(Long id, int status) {
            AccessGrant g = store.get(id);
            store.put(id, g.withStatus(status));
        }

        @Override
        public void revoke(Long id, Long operatorId) {
            updateStatus(id, AccessGrant.STATUS_REVOKED);
        }

        @Override
        public List<AccessGrant> findExpiredBefore(LocalDateTime before, int limit) {
            return store.values().stream()
                    .filter(g -> g.validTo().isBefore(before))
                    .filter(g -> g.status() == AccessGrant.STATUS_ACTIVE || g.status() == AccessGrant.STATUS_EXPIRED)
                    .limit(limit).toList();
        }
    }

    static class FakeBatchRepo implements SyncBatchRepository {
        final Map<Long, SyncBatch> batches = new LinkedHashMap<>();
        final List<SyncBatchItem> items = new ArrayList<>();
        final AtomicLong batchSeq = new AtomicLong();
        final AtomicLong itemSeq = new AtomicLong();

        @Override
        public long insert(SyncBatch b) {
            long id = batchSeq.incrementAndGet();
            batches.put(id, new SyncBatch(id, b.batchNo(), b.triggerType(), b.gateId(), 0, 0, 0,
                    SyncBatch.STATUS_RUNNING, LocalDateTime.now(), null));
            return id;
        }

        @Override
        public void start(Long id) {
        }

        @Override
        public void finish(Long id, int total, int success, int fail, int status) {
            SyncBatch b = batches.get(id);
            batches.put(id, new SyncBatch(id, b.batchNo(), b.triggerType(), b.gateId(), total, success, fail,
                    status, b.startedAt(), LocalDateTime.now()));
        }

        @Override
        public long addItem(SyncBatchItem item) {
            long id = itemSeq.incrementAndGet();
            items.add(new SyncBatchItem(id, item.batchId(), item.deviceId(), item.personNo(), item.action(),
                    item.status(), null, null, null));
            return id;
        }

        @Override
        public void updateItem(Long id, int status, String errorCode, String errorMsg) {
            for (int i = 0; i < items.size(); i++) {
                SyncBatchItem it = items.get(i);
                if (it.id().equals(id)) {
                    items.set(i, new SyncBatchItem(id, it.batchId(), it.deviceId(), it.personNo(), it.action(),
                            status, errorCode, errorMsg, LocalDateTime.now()));
                }
            }
        }

        @Override
        public Optional<SyncBatch> findById(Long id) {
            return Optional.ofNullable(batches.get(id));
        }

        @Override
        public List<SyncBatch> list(int offset, int limit) {
            return new ArrayList<>(batches.values());
        }

        @Override
        public long count() {
            return batches.size();
        }

        @Override
        public List<SyncBatchItem> items(Long batchId, int offset, int limit) {
            return items.stream().filter(i -> i.batchId().equals(batchId)).toList();
        }

        @Override
        public long countItems(Long batchId) {
            return items.stream().filter(i -> i.batchId().equals(batchId)).count();
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

    static class FakeVisitorRepo implements VisitorRepository {
        final Map<Long, Visitor> store = new LinkedHashMap<>();

        @Override
        public Optional<Visitor> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Visitor> findByIdCardHmac(String idCardHmac) {
            return Optional.empty();
        }

        @Override
        public Optional<Visitor> findByPhoneHmac(String phoneHmac) {
            return Optional.empty();
        }

        @Override
        public long insert(Visitor v) {
            long id = store.size() + 1;
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
        }

        @Override
        public Optional<String> findMaxPersonNoByPrefix(String prefix) {
            return Optional.empty();
        }
    }

    static class FakeAdapter implements DeviceAdapter {
        final List<PersonPayload> upserts = new ArrayList<>();
        final List<String> removals = new ArrayList<>();
        String failOnDeviceSn;

        @Override
        public boolean supports(Device.Channel channel) {
            return true;
        }

        @Override
        public void upsertPerson(Device device, PersonPayload payload) {
            if (device.getDeviceSn().equals(failOnDeviceSn)) {
                throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "模拟设备离线");
            }
            upserts.add(payload);
        }

        @Override
        public void removePerson(Device device, String personNo, String userType) {
            removals.add(device.getDeviceSn() + ":" + personNo);
        }

        @Override
        public JsonNode readParams(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeParams(Device device, Map<String, Object> partial) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearAllPersons(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> listPersonNos(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode readVersion(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void openDoor(Device device) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showMessage(Device device, String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setDeviceTime(Device device, LocalDateTime time) {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeStateStore implements PersonStateStore {
        final List<String> rows = new ArrayList<>();

        @Override
        public void save(Long deviceId, String personNo, int expectState, int actualState, boolean diff) {
            rows.add(deviceId + ":" + personNo + ":" + expectState + "/" + actualState + ":" + diff);
        }
    }

    /* ---------------- 固定数据 ---------------- */

    private FakeGrantRepo grantRepo;
    private FakeBatchRepo batchRepo;
    private FakeAppointmentRepo appointmentRepo;
    private FakeVisitorRepo visitorRepo;
    private FakeAdapter adapter;
    private InMemoryDeviceRepository deviceRepo;
    private CryptoService crypto;
    private DeviceCapabilityService capabilityService;
    private AppointmentGateService appointmentGateService;
    private DispatchService service;
    private Appointment appointment;
    private Visitor visitor;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");
        props.getDevice().setMaxRetry(1);
        crypto = new CryptoService(props);

        grantRepo = new FakeGrantRepo();
        batchRepo = new FakeBatchRepo();
        appointmentRepo = new FakeAppointmentRepo();
        visitorRepo = new FakeVisitorRepo();
        deviceRepo = new InMemoryDeviceRepository();
        adapter = new FakeAdapter();
        capabilityService = new DeviceCapabilityService(new DeviceAdapterFactory(List.of(adapter)), props);
        appointmentGateService = new AppointmentGateService(
                new InMemoryAppointmentGateRepository(),
                new GateService(InMemoryGateRepository.withUnassigned(), props));

        Device entry = new Device();
        entry.setId(1L);
        entry.setDeviceSn("T77R781116ZS");
        entry.setGateId(1L);
        entry.setStatus(1);
        deviceRepo.put(entry);

        Device exit = new Device();
        exit.setId(2L);
        exit.setDeviceSn("T77R781116ZS-EXIT");
        exit.setGateId(1L);
        exit.setStatus(1);
        deviceRepo.put(exit);

        visitor = new Visitor();
        visitor.setPersonNo("V202611160001");
        visitor.setName("赵六");
        visitor.setIdCardEnc(crypto.encrypt(ID_CARD));
        visitor.setIdCardMask(CryptoService.maskIdCard(ID_CARD));
        visitorRepo.insert(visitor);

        appointment = new Appointment();
        appointment.setAppointmentNo("A2026111600001");
        appointment.setVisitorId(visitor.getId());
        appointment.setStartTime(LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(9, 0)));
        appointment.setEndTime(LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(11, 0)));
        appointment.setStatus(Appointment.APPROVED);
        appointmentRepo.insert(appointment);

        service = new DispatchService(grantRepo, batchRepo, appointmentRepo, visitorRepo, deviceRepo,
                new DeviceAdapterFactory(List.of(adapter)), new DeviceGateway(props),
                new FakeStateStore(), crypto, props, capabilityService,
                new GateService(InMemoryGateRepository.withUnassigned(), props),
                appointmentGateService);
    }

    /* ---------------- 用例 ---------------- */

    @Test
    @DisplayName("下发成功：生成授权、推送全部设备、授权生效、预约单可通行")
    void dispatchSuccess() {
        DispatchService.DispatchResult result = service.dispatch(appointment.getId(), SyncBatch.TRIGGER_APPROVAL);

        assertEquals(2, result.total());
        assertEquals(2, result.success());
        assertEquals(0, result.fail());
        assertEquals(SyncBatch.STATUS_SUCCESS, result.status());

        AccessGrant grant = grantRepo.findByAppointmentId(appointment.getId()).orElseThrow();
        assertEquals(AccessGrant.STATUS_ACTIVE, grant.status());
        assertEquals(visitor.getPersonNo(), grant.personNo());
        assertEquals(1L, grant.gateId());

        // 有效期按自然日整段（C-60）
        LocalDate visitDate = appointment.getStartTime().toLocalDate();
        assertEquals(LocalDateTime.of(visitDate, LocalTime.MIN), grant.validFrom());
        assertEquals(LocalDateTime.of(visitDate, LocalTime.of(23, 59, 59)), grant.validTo());

        assertEquals(Appointment.GRANTED, appointment.getStatus());
        assertNotNull(appointment.getGrantedAt());

        assertEquals(2, batchRepo.items(result.batchId(), 0, 100).size());
        assertTrue(batchRepo.items(result.batchId(), 0, 100).stream()
                .allMatch(i -> i.status() == SyncBatchItem.STATUS_SUCCESS));
    }

    @Test
    @DisplayName("下发给设备的人员：证件号已解密、无照片（passAlgo 由适配层决定）")
    void payloadContainsDecryptedIdCard() {
        service.dispatch(appointment.getId(), SyncBatch.TRIGGER_APPROVAL);

        assertEquals(2, adapter.upserts.size());
        PersonPayload payload = adapter.upserts.get(0);
        assertEquals(ID_CARD, payload.getIdCard());
        assertEquals(visitor.getPersonNo(), payload.getPersonNo());
        assertEquals("white", payload.getUserType(),
                "本机固件不支持 visitor，访客按 white 下发，靠 company/department 区分");
        assertFalse(payload.requiresPhoto(), "访客下发不带照片");
        assertEquals(appointment.getStartTime().toLocalDate(), payload.getValidFrom());
    }

    @Test
    @DisplayName("预约单未通过时拒绝下发（3004）")
    void rejectWhenNotApproved() {
        appointment.setStatus(Appointment.PENDING_L1);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.dispatch(appointment.getId(), SyncBatch.TRIGGER_MANUAL));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), e.getCode());
    }

    @Test
    @DisplayName("门点下无设备：返回失败，不生成授权")
    void noDevices() {
        InMemoryDeviceRepository empty = new InMemoryDeviceRepository();
        AppProperties lonelyProps = new AppProperties();
        DispatchService lonely = new DispatchService(grantRepo, batchRepo, appointmentRepo, visitorRepo, empty,
                new DeviceAdapterFactory(List.of(adapter)), new DeviceGateway(lonelyProps),
                new FakeStateStore(), crypto, lonelyProps, capabilityService,
                new GateService(InMemoryGateRepository.withUnassigned(), lonelyProps),
                new AppointmentGateService(new InMemoryAppointmentGateRepository(),
                        new GateService(InMemoryGateRepository.withUnassigned(), lonelyProps)));

        DispatchService.DispatchResult result = lonely.dispatch(appointment.getId(), SyncBatch.TRIGGER_MANUAL);

        assertEquals(SyncBatch.STATUS_FAILED, result.status());
        assertTrue(grantRepo.findByAppointmentId(appointment.getId()).isEmpty());
    }

    @Test
    @DisplayName("多门点：每个批准门点各建一条授权；没设备的门点降级跳过不阻塞")
    void dispatchMultiGate() {
        AppProperties multiProps = new AppProperties();
        multiProps.getCrypto().setAesKey("unit-aes");
        multiProps.getCrypto().setHmacKey("unit-hmac");

        InMemoryGateRepository gateRepo = InMemoryGateRepository.withUnassigned();
        gateRepo.put(new Gate(10L, "EAST_GATE", "东门", Gate.TYPE_SIDE, Gate.STATUS_ENABLED, 1, null));
        gateRepo.put(new Gate(11L, "WEST_GATE", "西门", Gate.TYPE_SIDE, Gate.STATUS_ENABLED, 2, null));
        GateService multiGateService = new GateService(gateRepo, multiProps);
        AppointmentGateService multiGateSelection = new AppointmentGateService(
                new InMemoryAppointmentGateRepository(), multiGateService);

        // 只有东门装了设备；西门暂时没有设备，应该跳过而不是整体失败
        InMemoryDeviceRepository eastOnly = new InMemoryDeviceRepository();
        Device east = new Device();
        east.setId(101L);
        east.setDeviceSn("EAST-1");
        east.setDeviceName("东门终端");
        east.setGateId(10L);
        east.setChannel(Device.Channel.PULL);
        east.setStatus(1);
        eastOnly.put(east);

        FakeGrantRepo grants = new FakeGrantRepo();
        DispatchService multi = new DispatchService(grants, batchRepo, appointmentRepo, visitorRepo, eastOnly,
                new DeviceAdapterFactory(List.of(adapter)), new DeviceGateway(multiProps),
                new FakeStateStore(), crypto, multiProps, capabilityService,
                multiGateService, multiGateSelection);

        appointment.setStatus(Appointment.APPROVED);
        appointment.setGateId(10L);
        multiGateSelection.saveApproved(appointment, List.of(10L, 11L));

        DispatchService.DispatchResult result = multi.dispatch(appointment.getId(), SyncBatch.TRIGGER_MANUAL);

        assertEquals(1, result.total(), "只有东门有设备");
        assertEquals(1, result.success());
        assertEquals(SyncBatch.STATUS_SUCCESS, result.status());
        assertTrue(result.message().contains("西门"), result.message());

        assertEquals(2, grants.findAllByAppointmentId(appointment.getId()).size(),
                "两个批准门点各一条授权，方便西门装机后由对账补齐");
        assertEquals(AccessGrant.STATUS_ACTIVE,
                grants.findByAppointmentIdAndGate(appointment.getId(), 11L).orElseThrow().status());
    }

    @Test
    @DisplayName("部分设备失败：批次部分失败、授权保持待下发、预约单不变")
    void partialFailure() {
        adapter.failOnDeviceSn = "T77R781116ZS-EXIT";

        DispatchService.DispatchResult result = service.dispatch(appointment.getId(), SyncBatch.TRIGGER_APPROVAL);

        assertEquals(1, result.success());
        assertEquals(1, result.fail());
        assertEquals(SyncBatch.STATUS_PARTIAL, result.status());
        assertEquals(AccessGrant.STATUS_PENDING,
                grantRepo.findByAppointmentId(appointment.getId()).orElseThrow().status());
        assertEquals(Appointment.APPROVED, appointment.getStatus());
    }

    @Test
    @DisplayName("重复下发：不重复建授权，仍会重推一遍（幂等覆盖）")
    void redispatchIsIdempotent() {
        service.dispatch(appointment.getId(), SyncBatch.TRIGGER_APPROVAL);
        long firstGrantId = grantRepo.findByAppointmentId(appointment.getId()).orElseThrow().id();

        service.dispatch(appointment.getId(), SyncBatch.TRIGGER_MANUAL);

        assertEquals(1, grantRepo.store.size());
        assertEquals(firstGrantId, grantRepo.findByAppointmentId(appointment.getId()).orElseThrow().id());
        assertEquals(4, adapter.upserts.size(), "两次下发 × 两台设备");
        assertEquals(2, batchRepo.batches.size(), "每次下发都留批次记录");
    }

    @Test
    @DisplayName("撤销：设备删除 + 授权撤销 + 预约单撤销")
    void revoke() {
        service.dispatch(appointment.getId(), SyncBatch.TRIGGER_APPROVAL);
        DispatchService.DispatchResult result = service.revoke(appointment.getId(), 1L);

        assertEquals(2, result.success());
        assertEquals(AccessGrant.STATUS_REVOKED,
                grantRepo.findByAppointmentId(appointment.getId()).orElseThrow().status());
        assertEquals(Appointment.REVOKED, appointment.getStatus());
        assertEquals(2, adapter.removals.size());
    }

    @Test
    @DisplayName("审批事务未提交时不立刻下发，提交后才异步执行（防竞态）")
    void triggerWaitsForCommit() throws Exception {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.trigger(appointment);
            Thread.sleep(300);
            assertEquals(0, batchRepo.batches.size(), "事务未提交前不应下发");

            // 模拟事务提交
            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations()) {
                sync.afterCommit();
            }
            Thread.sleep(600);
            assertEquals(1, batchRepo.batches.size(), "提交后应异步下发一次");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("过期清理：过期 7 天以上的授权从设备删除")
    void cleanupExpired() {
        service.dispatch(appointment.getId(), SyncBatch.TRIGGER_APPROVAL);
        AccessGrant grant = grantRepo.findByAppointmentId(appointment.getId()).orElseThrow();
        // 把有效期改成 10 天前
        grantRepo.store.put(grant.id(), new AccessGrant(grant.id(), grant.subjectType(), grant.subjectId(),
                grant.personNo(), grant.appointmentId(), grant.gateId(),
                LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(10),
                AccessGrant.STATUS_ACTIVE));

        int cleaned = service.cleanupExpired();

        assertEquals(1, cleaned);
        assertEquals(AccessGrant.STATUS_EXPIRED, grantRepo.store.get(grant.id()).status());
        assertEquals(2, adapter.removals.size());
    }
}
