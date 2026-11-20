package com.fangke.visitor.device.pull;

import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.service.AccessEventRepository;
import com.fangke.visitor.device.service.CaptureUploader;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.device.service.VisitRecorder;
import com.fangke.visitor.dispatch.model.AccessGrant;
import com.fangke.visitor.dispatch.repository.AccessGrantRepository;
import com.fangke.visitor.employee.repository.EmployeeFaceRepository;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.gate.repository.InMemoryGateRepository;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.qr.service.QrTicketService;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮询式终端：心跳指令选择、自动登记、名单详情。
 *
 * <p>重点锁"平台不能主动推"这一约束下的行为：所有下发都必须经过 device_cmd 队列，
 * 且远程开门下发给设备后要立即出队（否则超时重排会反复开门）。
 */
class OpenApiDeviceServiceTest {

    private FakeCmdRepo cmdRepo;
    private DeviceRepository deviceRepo;
    private AccessGrantRepository grantRepo;
    private VisitorRepository visitorRepo;
    private CryptoService crypto;
    private OpenApiDeviceService service;
    private GateService gateService;
    private AppProperties props;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");
        props.getDevice().setPullHeartSeconds(30);
        props.getDevice().setPullCommandTimeoutSeconds(300);
        props.getDevice().setMaxRetry(3);
        crypto = new CryptoService(props);

        cmdRepo = new FakeCmdRepo();
        deviceRepo = mock(DeviceRepository.class);
        grantRepo = mock(AccessGrantRepository.class);
        visitorRepo = mock(VisitorRepository.class);

        gateService = new GateService(InMemoryGateRepository.withUnassigned(), props);
        service = new OpenApiDeviceService(deviceRepo, cmdRepo, grantRepo, visitorRepo,
                mock(EmployeeRepository.class), mock(EmployeeFaceRepository.class),
                mock(StorageService.class), crypto, mock(AccessEventRepository.class),
                mock(VisitRecorder.class), mock(CaptureUploader.class),
                mock(QrTicketService.class), gateService, props);
    }

    private Device device() {
        Device d = new Device();
        d.setId(1L);
        d.setDeviceSn("NEW-SN-001");
        d.setDeviceName("新终端");
        d.setGateId(1L);
        d.setDirection(1);
        d.setChannel(Device.Channel.PULL);
        d.setStatus(1);
        return d;
    }

    @Test
    @DisplayName("无指令：action 返回字符串 null（协议要求），并带心跳间隔")
    void idleHeartbeat() {
        Map<String, Object> data = service.heartbeat(device());

        assertEquals("null", data.get("action"));
        assertEquals(30, data.get("heartTime"));
        assertNull(data.get("actionPara"));
    }

    @Test
    @DisplayName("有待下发名单：心跳只回 action，不抢先置执行中；设备来取名单时才置执行中")
    void addPersonAction() {
        cmdRepo.enqueue(1L, PullCommandRepository.TYPE_ADD_PERSON, 5, "{\"personNo\":\"V1\"}", null);

        Map<String, Object> data = service.heartbeat(device());

        assertEquals("addDevicWhiteInfo", data.get("action"));
        assertFalse(cmdRepo.rows.get(0).dispatched(),
                "心跳不能抢先领取名单指令，否则设备随后取名单会拿到空列表");

        List<Map<String, Object>> list = service.claimPersonChanges(device(), PullCommandRepository.TYPE_ADD_PERSON);

        assertEquals(1, list.size());
        assertEquals("V1", list.get(0).get("idno"));
        assertTrue(cmdRepo.rows.get(0).dispatched(), "设备真正取名单时才进入执行中");
    }

    @Test
    @DisplayName("优先级：远程开门 > 校时 > 参数 > 名单")
    void actionPriority() {
        cmdRepo.enqueue(1L, PullCommandRepository.TYPE_ADD_PERSON, 5, "{\"personNo\":\"V1\"}", null);
        cmdRepo.enqueue(1L, PullCommandRepository.TYPE_SYNC_TIME, 8, null, null);
        cmdRepo.enqueue(1L, PullCommandRepository.TYPE_OPEN_DOOR, 10, null, null);

        assertEquals("setDeviceRemoteOpen", service.heartbeat(device()).get("action"));
        // 开门下发后立即出队：不能因为没回执而重放
        assertTrue(cmdRepo.rows.stream()
                .filter(r -> PullCommandRepository.TYPE_OPEN_DOOR.equals(r.cmdType))
                .allMatch(r -> r.status() == 2));

        assertEquals("syncDeviceTime", service.heartbeat(device()).get("action"));
        assertEquals("addDevicWhiteInfo", service.heartbeat(device()).get("action"));
    }

    @Test
    @DisplayName("心跳会先回收超时未回执的指令")
    void heartbeatRequeuesStale() {
        service.heartbeat(device());
        assertTrue(cmdRepo.requeued);
    }

    @Test
    @DisplayName("首次出现自动登记（channel=PULL），已存在则直接返回")
    void resolveOrRegister() {
        when(deviceRepo.findBySn("NEW-SN-001")).thenReturn(Optional.empty());
        when(deviceRepo.register(any(Device.class))).thenReturn(9L);

        Device created = service.resolveOrRegister("NEW-SN-001", "192.168.10.60");
        assertEquals(9L, created.getId());
        assertEquals(Device.Channel.PULL, created.getChannel());
        assertEquals("PULL-NEW-SN-001", created.getMac(), "mac 先占位，等设备上报真实值");
        assertEquals(gateService.unassignedGateId(), created.getGateId(),
                "新设备先放「待分配」门点，不能默认落到 1 号门");

        when(deviceRepo.findBySn("OLD")).thenReturn(Optional.of(device()));
        assertEquals("NEW-SN-001", service.resolveOrRegister("OLD", null).getDeviceSn());
    }

    @Test
    @DisplayName("名单索引：只返回该门点的生效授权，idno 用证件号、没有则用品编号")
    void personIndex() {
        AccessGrant grant = grant(1L, 2, 5L, "V000001");
        when(grantRepo.search(null, AccessGrant.STATUS_ACTIVE, 0, 2000)).thenReturn(List.of(grant));
        Visitor visitor = new Visitor();
        visitor.setId(5L);
        visitor.setPersonNo("V000001");
        visitor.setName("张三");
        visitor.setIdCardEnc(crypto.encrypt("110101199003070011"));
        when(visitorRepo.findById(5L)).thenReturn(Optional.of(visitor));

        List<Map<String, Object>> index = service.personIndex(device());

        assertEquals(1, index.size());
        assertEquals("110101199003070011", index.get(0).get("idno"));
        assertEquals(1, index.get(0).get("personVer"));
    }

    @Test
    @DisplayName("名单详情：访客无照片时 passAlgo=true（协议要求不带图也能下发）")
    void personDetailWithoutPhoto() {
        AccessGrant grant = grant(1L, 2, 5L, "V000001");
        when(grantRepo.search(null, AccessGrant.STATUS_ACTIVE, 0, 2000)).thenReturn(List.of(grant));
        Visitor visitor = new Visitor();
        visitor.setId(5L);
        visitor.setPersonNo("V000001");
        visitor.setName("张三");
        visitor.setIdCardEnc(crypto.encrypt("110101199003070011"));
        when(visitorRepo.findById(5L)).thenReturn(Optional.of(visitor));

        Map<String, Object> detail = service.personDetail(device(), "110101199003070011");

        assertEquals("white", detail.get("usertype"));
        assertEquals("张三", detail.get("name"));
        assertEquals(true, detail.get("passAlgo"));
        assertTrue(String.valueOf(detail.get("peoplestartdate")).startsWith("2026-11-19"));
        assertNull(service.personDetail(device(), "NOT-EXIST"));
    }

    @Test
    @DisplayName("名单回执：按 idno 反查 personNo 后完成对应指令")
    void syncWhiteResultCompletesCommand() {
        AccessGrant grant = grant(1L, 2, 5L, "V000001");
        when(grantRepo.search(null, AccessGrant.STATUS_ACTIVE, 0, 2000)).thenReturn(List.of(grant));
        Visitor visitor = new Visitor();
        visitor.setId(5L);
        visitor.setPersonNo("V000001");
        visitor.setName("张三");
        visitor.setIdCardEnc(crypto.encrypt("110101199003070011"));
        when(visitorRepo.findById(5L)).thenReturn(Optional.of(visitor));
        cmdRepo.enqueue(1L, PullCommandRepository.TYPE_ADD_PERSON, 5, "{\"personNo\":\"V000001\"}", null);

        service.syncWhiteResult(device(), "110101199003070011", 0, 0, "ok");

        assertEquals(2, cmdRepo.rows.get(0).status());
    }

    private AccessGrant grant(Long id, int subjectType, Long subjectId, String personNo) {
        return new AccessGrant(id, subjectType, subjectId, personNo, 100L, 1L,
                LocalDateTime.of(2026, 11, 19, 0, 0), LocalDateTime.of(2026, 11, 19, 23, 59, 59),
                AccessGrant.STATUS_ACTIVE);
    }

    /* ---------------- 内存指令队列 ---------------- */

    static class FakeCmdRepo implements PullCommandRepository {

        record RowState(Long id, Long deviceId, String cmdType, int priority, String payload,
                        int status, LocalDateTime startedAt) {
            boolean dispatched() {
                return status == 1;
            }
        }

        final List<RowState> rows = new ArrayList<>();
        boolean requeued = false;
        private long seq = 0;

        @Override
        public long enqueue(Long deviceId, String cmdType, int priority, String payload, Long batchId) {
            long id = ++seq;
            rows.add(new RowState(id, deviceId, cmdType, priority, payload, 0, null));
            return id;
        }

        @Override
        public List<Row> listPending(Long deviceId, String cmdType, int limit) {
            return rows.stream()
                    .filter(r -> r.deviceId().equals(deviceId) && r.cmdType().equals(cmdType) && r.status() == 0)
                    .limit(limit)
                    .map(r -> new Row(r.id(), r.cmdType(), r.priority(), r.payload(), 0))
                    .toList();
        }

        @Override
        public boolean hasPending(Long deviceId, String cmdType) {
            return rows.stream().anyMatch(r -> r.deviceId().equals(deviceId)
                    && r.cmdType().equals(cmdType) && r.status() == 0);
        }

        @Override
        public void markDispatching(List<Long> ids, LocalDateTime at) {
            for (Long id : ids) {
                replaceStatus(id, 1);
            }
        }

        @Override
        public void markDone(List<Long> ids, int status, String errorMsg) {
            for (Long id : ids) {
                replaceStatus(id, status);
            }
        }

        @Override
        public int markDoneByPerson(Long deviceId, String cmdType, String personNo, int status, String message) {
            int affected = 0;
            for (int i = 0; i < rows.size(); i++) {
                RowState r = rows.get(i);
                if (r.deviceId().equals(deviceId) && r.cmdType().equals(cmdType)
                        && r.status() != 2 && (r.payload() == null || r.payload().contains(personNo))) {
                    rows.set(i, new RowState(r.id(), r.deviceId(), r.cmdType(), r.priority(), r.payload(),
                            status, r.startedAt()));
                    affected++;
                }
            }
            return affected;
        }

        @Override
        public int requeueStale(Long deviceId, LocalDateTime dispatchedBefore, int maxRetry) {
            requeued = true;
            return 0;
        }

        @Override
        public long countPending(Long deviceId) {
            return rows.stream().filter(r -> r.deviceId().equals(deviceId) && r.status() < 2).count();
        }

        @Override
        public Optional<Row> findById(Long id) {
            return rows.stream().filter(r -> r.id().equals(id))
                    .map(r -> new Row(r.id(), r.cmdType(), r.priority(), r.payload(), 0)).findFirst();
        }

        private void replaceStatus(Long id, int status) {
            for (int i = 0; i < rows.size(); i++) {
                RowState r = rows.get(i);
                if (r.id().equals(id)) {
                    rows.set(i, new RowState(r.id(), r.deviceId(), r.cmdType(), r.priority(), r.payload(),
                            status, LocalDateTime.now()));
                }
            }
        }
    }
}
