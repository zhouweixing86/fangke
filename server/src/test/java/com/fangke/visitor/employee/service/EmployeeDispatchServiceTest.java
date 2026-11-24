package com.fangke.visitor.employee.service;

import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.PersonPayload;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.dispatch.model.AccessGrant;
import com.fangke.visitor.dispatch.repository.AccessGrantRepository;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeFaceRepository;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 员工名单下发（C-66）：入职即按门点建授权并推送，停用即撤销并从设备删除。
 */
class EmployeeDispatchServiceTest {

    private EmployeeRepository employeeRepository;
    private EmployeeFaceRepository faceRepository;
    private StorageService storageService;
    private DeviceRepository deviceRepository;
    private AccessGrantRepository grantRepository;
    private DeviceAdapterFactory adapterFactory;
    private DeviceGateway gateway;
    private DeviceAdapter adapter;
    private EmployeeDispatchService service;

    private final Device eastDevice = device(1L, "EAST-1", 10L);
    private final List<AccessGrant> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");

        employeeRepository = mock(EmployeeRepository.class);
        faceRepository = mock(EmployeeFaceRepository.class);
        storageService = mock(StorageService.class);
        deviceRepository = mock(DeviceRepository.class);
        grantRepository = mock(AccessGrantRepository.class);
        adapterFactory = mock(DeviceAdapterFactory.class);
        gateway = mock(DeviceGateway.class);
        adapter = mock(DeviceAdapter.class);

        GateService gateService = mock(GateService.class);
        when(gateService.listEnabled()).thenReturn(List.of(
                new Gate(10L, "EAST_GATE", "东门", Gate.TYPE_SIDE, Gate.STATUS_ENABLED, 1, null),
                new Gate(11L, "WEST_GATE", "西门", Gate.TYPE_SIDE, Gate.STATUS_ENABLED, 2, null),
                new Gate(2L, Gate.CODE_UNASSIGNED, "待分配", Gate.TYPE_UNASSIGNED, Gate.STATUS_ENABLED, 999, null)));

        when(deviceRepository.findActiveByGate(10L)).thenReturn(List.of(eastDevice));
        when(deviceRepository.findActiveByGate(11L)).thenReturn(List.of());
        when(adapterFactory.resolve(any())).thenReturn(adapter);
        when(grantRepository.findBySubjectAndGate(anyInt(), anyLong(), anyLong()))
                .thenAnswer(inv -> Optional.empty());
        when(grantRepository.insert(any())).thenAnswer(inv -> {
            AccessGrant g = inv.getArgument(0);
            stored.add(g);
            return (long) stored.size();
        });
        // 让 gateway 真正执行 action，便于验证适配器被调用
        when(gateway.execute(any(), anyString(), any())).thenAnswer(inv -> {
            Function<Device, Object> action = inv.getArgument(2);
            return action.apply(inv.getArgument(0));
        });

        service = new EmployeeDispatchService(employeeRepository, faceRepository, storageService,
                deviceRepository, grantRepository, adapterFactory, gateway,
                mock(DeviceCapabilityService.class), gateService, new CryptoService(props));
    }

    private static Device device(Long id, String sn, Long gateId) {
        Device d = new Device();
        d.setId(id);
        d.setDeviceSn(sn);
        d.setGateId(gateId);
        d.setStatus(1);
        return d;
    }

    private static Employee employee(int status) {
        Employee e = new Employee();
        e.setId(7L);
        e.setEmpNo("000123");
        e.setPersonNo("E000123");
        e.setName("张三");
        e.setStatus(status);
        e.setValidFrom(LocalDate.of(2026, 1, 1));
        return e;
    }

    @Test
    @DisplayName("在岗员工：每个启用门点一条授权（排除待分配），并推送到有设备的门点")
    void syncCreatesGrantPerGate() {
        EmployeeDispatchService.SyncResult result = service.sync(employee(Employee.STATUS_ACTIVE));

        assertEquals(2, stored.size(), "东门+西门各一条，待分配不建授权");
        assertTrue(stored.stream().allMatch(g -> g.subjectType() == AccessGrant.SUBJECT_EMPLOYEE));
        assertTrue(stored.stream().allMatch(g -> g.status() == AccessGrant.STATUS_ACTIVE));
        assertEquals("E000123", stored.get(0).personNo());
        assertEquals(LocalDate.of(9999, 12, 31).atTime(23, 59, 59), stored.get(0).validTo(),
                "员工没填离职日期 → 长期有效");

        assertEquals(1, result.devices(), "只有东门有设备");
        assertEquals(1, result.success());
        ArgumentCaptor<PersonPayload> payload = ArgumentCaptor.forClass(PersonPayload.class);
        verify(adapter).upsertPerson(any(), payload.capture());
        assertEquals(PersonPayload.USER_TYPE_WHITE, payload.getValue().getUserType());
    }

    @Test
    @DisplayName("同门点挂了车闸相机：人员名单只发给人行终端")
    void syncSkipsVehicleCamera() {
        Device camera = device(3L, "CAR-EAST-1", 10L);
        camera.setChannel(Device.Channel.VEHICLE);
        when(deviceRepository.findActiveByGate(10L)).thenReturn(List.of(eastDevice, camera));

        EmployeeDispatchService.SyncResult result = service.sync(employee(Employee.STATUS_ACTIVE));

        assertEquals(1, result.devices(), "车闸相机不算人员下发目标");
        verify(adapter).upsertPerson(eq(eastDevice), any());
        verify(adapter, never()).upsertPerson(eq(camera), any());
    }

    @Test
    @DisplayName("停用员工：不推送，授权直接置撤销")
    void syncDisabledEmployeeRevokesGrant() {
        service.sync(employee(Employee.STATUS_DISABLED));

        assertEquals(2, stored.size());
        assertTrue(stored.stream().allMatch(g -> g.status() == AccessGrant.STATUS_REVOKED));
        verify(adapter, never()).upsertPerson(any(), any());
    }

    @Test
    @DisplayName("停用并清理：撤销已有授权并从设备删除")
    void revokeRemovesFromDevices() {
        AccessGrant grant = new AccessGrant(3L, AccessGrant.SUBJECT_EMPLOYEE, 7L, "E000123",
                null, 10L, LocalDate.now().atStartOfDay(), LocalDate.now().atTime(23, 59, 59),
                AccessGrant.STATUS_ACTIVE);
        when(grantRepository.findBySubject(AccessGrant.SUBJECT_EMPLOYEE, 7L)).thenReturn(List.of(grant));

        EmployeeDispatchService.SyncResult result = service.revoke(employee(Employee.STATUS_DISABLED), 1L);

        verify(adapter).removePerson(eq(eastDevice), eq("E000123"), eq(PersonPayload.USER_TYPE_WHITE));
        verify(grantRepository).revoke(3L, 1L);
        assertEquals(1, result.success());
    }

    @Test
    @DisplayName("全量同步：在岗下发、停用撤销")
    void syncAllHandlesBothGroups() {
        Employee active = employee(Employee.STATUS_ACTIVE);
        Employee disabled = employee(Employee.STATUS_DISABLED);
        disabled.setId(8L);
        disabled.setPersonNo("E000124");
        when(employeeRepository.search(null, Employee.STATUS_ACTIVE, 0, 10000)).thenReturn(List.of(active));
        when(employeeRepository.search(null, Employee.STATUS_DISABLED, 0, 10000)).thenReturn(List.of(disabled));
        when(grantRepository.findBySubject(anyInt(), anyLong())).thenReturn(List.of());

        EmployeeDispatchService.SyncResult result = service.syncAll();

        assertEquals(2, result.employees());
        verify(grantRepository, atLeastOnce()).insert(any());
    }
}
