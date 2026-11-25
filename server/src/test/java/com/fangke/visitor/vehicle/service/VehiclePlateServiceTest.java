package com.fangke.visitor.vehicle.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentGateRepository;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PlatePayload;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.vehicle.cloud.VehiclePlateCloudService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 车闸车牌名单（docs/18）：来源合并、去重、门点过滤、厂商协议未接入时的降级。
 */
class VehiclePlateServiceTest {

    private EmployeeRepository employeeRepo;
    private AppointmentRepository appointmentRepo;
    private AppointmentGateRepository appointmentGateRepo;
    private VisitorRepository visitorRepo;
    private VehicleDeviceService deviceService;
    private DeviceCapabilityService capabilityService;
    private VehiclePlateService service;
    private VehiclePlateCloudService cloudService;

    @BeforeEach
    void setUp() {
        employeeRepo = mock(EmployeeRepository.class);
        appointmentRepo = mock(AppointmentRepository.class);
        appointmentGateRepo = mock(AppointmentGateRepository.class);
        visitorRepo = mock(VisitorRepository.class);
        deviceService = mock(VehicleDeviceService.class);
        capabilityService = mock(DeviceCapabilityService.class);
        cloudService = mock(VehiclePlateCloudService.class);
        when(cloudService.enabled()).thenReturn(false);
        when(cloudService.reconcile(any())).thenReturn(
                new VehiclePlateCloudService.SyncResult(false, true, 0, 0, 0, 0, 0, "未启用", List.of()));
        service = new VehiclePlateService(employeeRepo, appointmentRepo, appointmentGateRepo, visitorRepo,
                deviceService, mock(DeviceAdapterFactory.class), capabilityService, cloudService,
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    private Employee employee(String plate, String name) {
        Employee e = new Employee();
        e.setId(1L);
        e.setName(name);
        e.setPersonNo("E000001");
        e.setPlateNo(plate);
        e.setValidFrom(LocalDate.of(2025, 1, 1));
        e.setValidTo(LocalDate.of(2033, 1, 1));
        return e;
    }

    private Appointment appointment(String plate, long visitorId, Long appointmentId) {
        Appointment a = new Appointment();
        a.setId(appointmentId);
        a.setVisitorId(visitorId);
        a.setPlateNo(plate);
        a.setStartTime(LocalDateTime.now().withHour(9).withMinute(0));
        a.setEndTime(LocalDateTime.now().withHour(18).withMinute(0));
        return a;
    }

    @Test
    @DisplayName("快照：员工车牌 + 当天访客车牌，同车牌去重且员工优先")
    void snapshotMergesEmployeeAndVisitor() {
        when(employeeRepo.findActiveWithPlate()).thenReturn(List.of(employee("京a12345", "张三")));
        when(appointmentRepo.findPlateAppointmentsOnDate(any())).thenReturn(List.of(
                appointment("京A12345", 9L, 100L),
                appointment("京B67890", 9L, 101L)));
        when(appointmentGateRepo.findGateIds(eq(101L), anyInt())).thenReturn(List.of(3L));
        when(appointmentGateRepo.findGateIds(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any(), anyInt()))
                .thenReturn(java.util.Map.of(100L, List.of(3L), 101L, List.of(3L)));
        Visitor visitor = new Visitor();
        visitor.setId(9L);
        visitor.setName("李四");
        when(visitorRepo.findById(9L)).thenReturn(Optional.of(visitor));
        when(visitorRepo.findNamesByIds(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any()))
                .thenReturn(java.util.Map.of(9L, "李四"));

        List<PlatePayload> plates = service.snapshot(3L);

        assertEquals(2, plates.size());
        assertEquals("京A12345", plates.get(0).plateNo());
        assertEquals(PlatePayload.OWNER_EMPLOYEE, plates.get(0).ownerType(), "同车牌员工优先");
        assertEquals("京B67890", plates.get(1).plateNo());
        assertEquals(PlatePayload.OWNER_VISITOR, plates.get(1).ownerType());
        assertEquals("李四", plates.get(1).ownerName());
        assertEquals(2, service.countByOwner(plates).get("total"));
    }

    @Test
    @DisplayName("快照：按门点过滤，未批准该门点的访客车牌不进清单")
    void snapshotFiltersByGate() {
        when(employeeRepo.findActiveWithPlate()).thenReturn(List.of());
        when(appointmentRepo.findPlateAppointmentsOnDate(any()))
                .thenReturn(List.of(appointment("京B67890", 9L, 101L)));
        when(appointmentGateRepo.findGateIds(eq(101L), anyInt())).thenReturn(List.of(4L));
        when(appointmentGateRepo.findGateIds(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any(), anyInt()))
                .thenReturn(java.util.Map.of(101L, List.of(4L)));
        when(visitorRepo.findNamesByIds(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any()))
                .thenReturn(java.util.Map.of(9L, "李四"));

        assertTrue(service.snapshot(3L).isEmpty());
        assertEquals(1, service.snapshot(4L).size());
    }

    @Test
    @DisplayName("访客被拉黑：即使预约单有效也不进车牌清单（已下发的下一轮会被删）")
    void snapshotSkipsBlacklistedVisitor() {
        when(employeeRepo.findActiveWithPlate()).thenReturn(List.of());
        when(appointmentRepo.findPlateAppointmentsOnDate(any()))
                .thenReturn(List.of(appointment("京T11111", 9L, 200L)));
        when(appointmentGateRepo.findGateIds(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any(), anyInt()))
                .thenReturn(java.util.Map.of(200L, List.of(1L)));
        when(visitorRepo.findNamesByIds(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any()))
                .thenReturn(java.util.Map.of(9L, "黑名单访客"));
        when(visitorRepo.findBlacklistedIds(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any()))
                .thenReturn(java.util.Set.of(9L));

        assertTrue(service.snapshot(1L).isEmpty(), "拉黑访客的车牌不应下发");
    }

    @Test
    @DisplayName("下发：厂商协议未接入的车闸相机记 PENDING_VENDOR，不抛异常")
    void syncMarksPendingVendor() {
        when(employeeRepo.findActiveWithPlate()).thenReturn(List.of(employee("京A12345", "张三")));
        when(appointmentRepo.findPlateAppointmentsOnDate(any())).thenReturn(List.of());
        Device camera = new Device();
        camera.setId(20L);
        camera.setDeviceSn("CAR-001");
        camera.setChannel(Device.Channel.VEHICLE);
        camera.setGateId(3L);
        when(deviceService.list(3L)).thenReturn(List.of(camera));
        when(capabilityService.supports(camera, DeviceCapability.PLATE_UPSERT)).thenReturn(false);

        VehiclePlateService.SyncResult result = service.sync(3L);

        assertEquals(1, result.plateTotal());
        assertEquals(1, result.pendingDevices());
        assertEquals("PENDING_VENDOR", result.devices().get(0).status());
        assertEquals(1, result.devices().get(0).plateCount());
    }

    @Test
    @DisplayName("下发：没有车闸相机时只返回清单，不报错")
    void syncWithoutDevices() {
        when(employeeRepo.findActiveWithPlate()).thenReturn(List.of(employee("京A12345", "张三")));
        when(appointmentRepo.findPlateAppointmentsOnDate(any())).thenReturn(List.of());
        when(deviceService.list(any())).thenReturn(List.of());

        VehiclePlateService.SyncResult result = service.sync(null);

        assertEquals(1, result.plateTotal());
        assertTrue(result.devices().isEmpty());
        assertEquals(0, result.pendingDevices());
    }
}
