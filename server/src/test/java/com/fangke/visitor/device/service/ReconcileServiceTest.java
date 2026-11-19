package com.fangke.visitor.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.PersonPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileServiceTest {

    /** 只实现对账用到的两个方法，其余显式不支持（避免误用）。 */
    static class FakeAdapter implements DeviceAdapter {
        final Set<String> persons = new LinkedHashSet<>();
        final List<String> removed = new ArrayList<>();

        @Override
        public boolean supports(Device.Channel channel) {
            return true;
        }

        @Override
        public List<String> listPersonNos(Device device) {
            return new ArrayList<>(persons);
        }

        @Override
        public void removePerson(Device device, String personNo, String userType) {
            if (!persons.remove(personNo)) {
                throw new IllegalStateException("删除失败，设备上不存在：" + personNo);
            }
            removed.add(personNo);
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
        public void upsertPerson(Device device, PersonPayload payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearAllPersons(Device device) {
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
        record Row(Long deviceId, String personNo, int expect, int actual, boolean diff) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void save(Long deviceId, String personNo, int expectState, int actualState, boolean diff) {
            rows.add(new Row(deviceId, personNo, expectState, actualState, diff));
        }
    }

    private FakeAdapter adapter;
    private FakeStateStore stateStore;
    private InMemoryDeviceRepository deviceRepository;
    private DeviceGateway gateway;
    private ReconcileService service;
    private BiFunction<Long, Instant, List<ExpectedPersonPort.ExpectedPerson>> expectedSupplier;

    @BeforeEach
    void setUp() {
        Device device = new Device();
        device.setId(1L);
        device.setDeviceSn("T77R781116ZS");
        device.setMac("0A:0C:E8:68:11:16");
        device.setGateId(1L);
        device.setChannel(Device.Channel.LAN);
        deviceRepository = new InMemoryDeviceRepository().put(device);

        adapter = new FakeAdapter();
        stateStore = new FakeStateStore();
        gateway = new DeviceGateway(new AppProperties());

        List<ExpectedPersonPort.ExpectedPerson> expected = List.of(
                new ExpectedPersonPort.ExpectedPerson("V1", PersonPayload.USER_TYPE_VISITOR));
        expectedSupplier = (gateId, now) -> expected;

        service = new ReconcileService(deviceRepository,
                new DeviceAdapterFactory(List.of(adapter)),
                gateway,
                expectedSupplier::apply,
                stateStore);
    }

    @Test
    @DisplayName("三向比对：缺失只记录、多余直接删除")
    void diffAndRemove() {
        expectedSupplier = (gateId, now) -> List.of(
                new ExpectedPersonPort.ExpectedPerson("V1", PersonPayload.USER_TYPE_VISITOR),
                new ExpectedPersonPort.ExpectedPerson("V2", PersonPayload.USER_TYPE_VISITOR));
        service = new ReconcileService(deviceRepository, new DeviceAdapterFactory(List.of(adapter)),
                gateway, expectedSupplier::apply, stateStore);

        adapter.persons.addAll(Set.of("V2", "V3"));

        ReconcileService.Result result = service.reconcile(1L);

        assertEquals(2, result.expectedCount());
        assertEquals(2, result.actualCount());
        assertEquals(List.of("V1"), result.missingInDevice());
        assertEquals(List.of("V3"), result.extraInDevice());
        assertEquals(1, result.removed());
        assertEquals(0, result.failedRemoval());

        assertTrue(adapter.persons.contains("V2"), "应保留一致的人员");
        assertTrue(!adapter.persons.contains("V3"), "多余人员应被删除");
        assertEquals(List.of("V3"), adapter.removed);
    }

    @Test
    @DisplayName("完全一致时不做任何删除")
    void noChangeWhenConsistent() {
        adapter.persons.add("V1");

        ReconcileService.Result result = service.reconcile(1L);

        assertEquals(0, result.missingInDevice().size());
        assertEquals(0, result.extraInDevice().size());
        assertEquals(0, result.removed());
        assertTrue(adapter.removed.isEmpty());
    }

    @Test
    @DisplayName("对账结果落 person_device_state：缺失/多余标 diff=1")
    void persistsState() {
        expectedSupplier = (gateId, now) -> List.of(
                new ExpectedPersonPort.ExpectedPerson("V1", PersonPayload.USER_TYPE_VISITOR));
        service = new ReconcileService(deviceRepository, new DeviceAdapterFactory(List.of(adapter)),
                gateway, expectedSupplier::apply, stateStore);
        adapter.persons.add("V9");

        service.reconcile(1L);

        assertTrue(stateStore.rows.stream().anyMatch(r ->
                r.personNo().equals("V1") && r.expect() == PersonStateStore.EXPECT_IN
                        && r.actual() == PersonStateStore.ACTUAL_OUT && r.diff()));
        assertTrue(stateStore.rows.stream().anyMatch(r ->
                r.personNo().equals("V9") && r.expect() == PersonStateStore.EXPECT_OUT
                        && r.actual() == PersonStateStore.ACTUAL_OUT && !r.diff()));
    }

    @Test
    @DisplayName("设备不存在时抛错，避免静默成功")
    void unknownDevice() {
        assertThrows(IllegalArgumentException.class, () -> service.reconcile(999L));
    }

    @Test
    @DisplayName("设备上线回调异步触发对账，不阻塞心跳线程")
    void onlineListenerTriggers() throws Exception {
        adapter.persons.add("V1");
        service.onDeviceOnline(deviceRepository.findById(1L).orElseThrow());
        // 给异步线程一点时间
        Thread.sleep(800);
        assertTrue(stateStore.rows.size() >= 1);
    }
}
