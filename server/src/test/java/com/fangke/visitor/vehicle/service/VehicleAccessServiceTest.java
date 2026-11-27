package com.fangke.visitor.vehicle.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentGateRepository;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.storage.StorageService;
import com.fangke.visitor.vehicle.model.VehicleEvent;
import com.fangke.visitor.vehicle.repository.VehicleEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 车辆通行判定（C-68）：员工长期授权、访客当天授权 + 门点集合。
 */
class VehicleAccessServiceTest {

    private EmployeeRepository employeeRepository;
    private AppointmentRepository appointmentRepository;
    private AppointmentGateRepository gateRepository;
    private VisitorRepository visitorRepository;
    private VehicleEventRepository eventRepository;
    private VehicleAccessService service;

    private final LocalDateTime at = LocalDateTime.of(2026, 11, 19, 9, 30);

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        appointmentRepository = mock(AppointmentRepository.class);
        gateRepository = mock(AppointmentGateRepository.class);
        visitorRepository = mock(VisitorRepository.class);
        eventRepository = mock(VehicleEventRepository.class);
        service = new VehicleAccessService(employeeRepository, appointmentRepository, gateRepository,
                visitorRepository, eventRepository, mock(StorageService.class));
    }

    private Employee employee(LocalDate validTo) {
        Employee e = new Employee();
        e.setId(7L);
        e.setName("张三");
        e.setStatus(Employee.STATUS_ACTIVE);
        e.setValidFrom(LocalDate.of(2026, 1, 1));
        e.setValidTo(validTo);
        return e;
    }

    @Test
    @DisplayName("员工车牌在岗且未过期：全部启用门点放行")
    void employeeAllowed() {
        when(employeeRepository.findActiveByPlateNo("京A88888")).thenReturn(Optional.of(employee(null)));

        VehicleAccessService.Decision d = service.judge(" 京a 88888 ", 10L, at);

        assertTrue(d.allowed());
        assertEquals(VehicleEvent.TYPE_EMPLOYEE, d.matchedType());
        assertEquals("张三", d.matchedName());
        assertEquals("员工车辆", d.reason());
    }

    @Test
    @DisplayName("员工车牌但通行证已过期：拒绝，仍标出匹配到的员工")
    void employeeExpired() {
        when(employeeRepository.findActiveByPlateNo("京A88888"))
                .thenReturn(Optional.of(employee(LocalDate.of(2026, 11, 18))));

        VehicleAccessService.Decision d = service.judge("京A88888", 10L, at);

        assertFalse(d.allowed());
        assertEquals(VehicleEvent.TYPE_EMPLOYEE, d.matchedType());
        assertTrue(d.reason().contains("过期"), d.reason());
    }

    @Test
    @DisplayName("访客车牌当天已授权且门点命中：放行")
    void visitorAllowed() {
        Appointment appointment = new Appointment();
        appointment.setId(19L);
        appointment.setVisitorId(15L);
        when(employeeRepository.findActiveByPlateNo("京B12345")).thenReturn(Optional.empty());
        when(appointmentRepository.findByPlateNoOnDate("京B12345", at.toLocalDate()))
                .thenReturn(List.of(appointment));
        when(gateRepository.findGateIds(19L, AppointmentGateRepository.STAGE_APPROVED))
                .thenReturn(List.of(10L, 11L));
        Visitor visitor = new Visitor();
        visitor.setName("李小红");
        when(visitorRepository.findById(15L)).thenReturn(Optional.of(visitor));

        VehicleAccessService.Decision d = service.judge("京B12345", 11L, at);

        assertTrue(d.allowed());
        assertEquals(VehicleEvent.TYPE_VISITOR, d.matchedType());
        assertEquals("李小红", d.matchedName());
    }

    @Test
    @DisplayName("访客车牌已授权但不含当前门点：拒绝")
    void visitorGateMismatch() {
        Appointment appointment = new Appointment();
        appointment.setId(19L);
        appointment.setVisitorId(15L);
        when(employeeRepository.findActiveByPlateNo("京B12345")).thenReturn(Optional.empty());
        when(appointmentRepository.findByPlateNoOnDate(eq("京B12345"), any()))
                .thenReturn(List.of(appointment));
        when(gateRepository.findGateIds(anyLong(), eq(AppointmentGateRepository.STAGE_APPROVED)))
                .thenReturn(List.of(10L));

        VehicleAccessService.Decision d = service.judge("京B12345", 11L, at);

        assertFalse(d.allowed());
        assertTrue(d.reason().contains("不包含当前门点"), d.reason());
    }

    @Test
    @DisplayName("未登记车牌：拒绝")
    void unknownPlate() {
        when(employeeRepository.findActiveByPlateNo("京C00000")).thenReturn(Optional.empty());
        when(appointmentRepository.findByPlateNoOnDate(eq("京C00000"), any())).thenReturn(List.of());

        VehicleAccessService.Decision d = service.judge("京C00000", 10L, at);

        assertFalse(d.allowed());
        assertTrue(d.reason().contains("未登记"), d.reason());
    }

    @Test
    @DisplayName("车牌为空或门点缺失：拒绝（宁可拦下，不误放）")
    void guardAgainstMissingInput() {
        assertFalse(service.judge(null, 10L, at).allowed());
        assertFalse(service.judge("京A88888", null, at).allowed());
    }

    @Test
    @DisplayName("团队预约：一辆车只记主访客一条，同行人不拆行（同行人在预约详情里看）")
    void visitorTeamRecordedAsMainVisitorOnly() {
        Appointment appointment = new Appointment();
        appointment.setId(19L);
        appointment.setAppointmentNo("V202611190001");
        appointment.setVisitorId(15L);
        when(employeeRepository.findActiveByPlateNo("京B12345")).thenReturn(Optional.empty());
        when(appointmentRepository.findByPlateNoOnDate("京B12345", at.toLocalDate()))
                .thenReturn(List.of(appointment));
        when(gateRepository.findGateIds(19L, AppointmentGateRepository.STAGE_APPROVED))
                .thenReturn(List.of(10L));
        Visitor visitor = new Visitor();
        visitor.setName("李小红");
        visitor.setPersonNo("V202611190001");
        when(visitorRepository.findById(15L)).thenReturn(Optional.of(visitor));
        // 就算预约里有同行人，也不再给通行记录拆行
        when(appointmentRepository.findMates(19L)).thenReturn(List.of(
                new AppointmentMate(1L, 19L, "李小红", null, null, null, null, null, true, "V202611190001"),
                new AppointmentMate(2L, 19L, "王五", null, null, null, null, null, false, null)));

        VehicleAccessService.Decision decision = service.judge("京B12345", 10L, at);

        assertTrue(decision.allowed());
        assertEquals("李小红", decision.matchedName(), "只记主访客");
        assertEquals("V202611190001", decision.personNo());
        verify(appointmentRepository, never()).findMates(anyLong());
    }
}
