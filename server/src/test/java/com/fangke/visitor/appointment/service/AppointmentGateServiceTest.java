package com.fangke.visitor.appointment.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.InMemoryAppointmentGateRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.repository.InMemoryGateRepository;
import com.fangke.visitor.gate.service.GateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多门点申请/批准集合（C-64）：可增可减、全部门点快照、历史单据兜底。
 */
class AppointmentGateServiceTest {

    private InMemoryGateRepository gateRepo;
    private InMemoryAppointmentGateRepository store;
    private GateService gateService;
    private AppointmentGateService service;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        gateRepo = InMemoryGateRepository.withUnassigned();
        gateRepo.put(new Gate(10L, "EAST_GATE", "东门", Gate.TYPE_SIDE, Gate.STATUS_ENABLED, 1, null));
        gateRepo.put(new Gate(11L, "WEST_GATE", "西门", Gate.TYPE_SIDE, Gate.STATUS_ENABLED, 2, null));
        gateRepo.put(new Gate(12L, "GARAGE", "车库门", Gate.TYPE_GARAGE, Gate.STATUS_ENABLED, 3, null));

        AppProperties props = new AppProperties();
        gateService = new GateService(gateRepo, props);
        store = new InMemoryAppointmentGateRepository();
        service = new AppointmentGateService(store, gateService);

        appointment = new Appointment();
        appointment.setId(1L);
        appointment.setAppointmentNo("A2026111900001");
    }

    @Test
    @DisplayName("申请「全部门点」：展开成当时的启用门点，主门点取第一台")
    void applyAllGates() {
        AppointmentGateService.AppliedSelection selection =
                service.resolveApplication(null, null, Appointment.GATE_SCOPE_ALL);

        assertEquals(Appointment.GATE_SCOPE_ALL, selection.scope());
        assertEquals(List.of(10L, 11L, 12L), selection.gateIds(), "排除「待分配」，按 sort_no 排序");
        assertEquals(10L, selection.primaryGateId());
    }

    @Test
    @DisplayName("申请多个门点：校验存在/启用、去重、主门点取第一个")
    void applySelectedGates() {
        AppointmentGateService.AppliedSelection selection =
                service.resolveApplication(null, List.of(11L, 10L, 11L), Appointment.GATE_SCOPE_SPECIFIED);

        assertEquals(List.of(11L, 10L), selection.gateIds());
        assertEquals(11L, selection.primaryGateId());
        assertEquals(Appointment.GATE_SCOPE_SPECIFIED, selection.scope());
    }

    @Test
    @DisplayName("不许申请「待分配」门点")
    void applyUnassignedRejected() {
        Long unassigned = gateService.unassignedGateId();
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.resolveApplication(null, List.of(unassigned), null));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("不传门点时回落默认门点（单门部署兼容）")
    void applyDefaultsToConfiguredGate() {
        AppProperties props = new AppProperties();
        props.getGate().setDefaultId(11L);
        AppointmentGateService withDefault = new AppointmentGateService(store,
                new GateService(gateRepo, props));

        AppointmentGateService.AppliedSelection selection = withDefault.resolveApplication(null, null, null);
        assertEquals(List.of(11L), selection.gateIds());
    }

    @Test
    @DisplayName("审批可增可减：显式集合覆盖申请集合")
    void approveCanAddAndRemove() {
        service.saveApplied(appointment.getId(), List.of(10L));

        List<Long> approved = service.saveApproved(appointment, List.of(11L, 12L));
        assertEquals(List.of(11L, 12L), approved);
        assertEquals(List.of(10L), store.findGateIds(appointment.getId(),
                com.fangke.visitor.appointment.repository.AppointmentGateRepository.STAGE_APPLIED),
                "申请集合不受审批调整影响，便于留痕对比");
    }

    @Test
    @DisplayName("审批不传集合时继承申请集合；全部门点在此快照")
    void approveInheritsWhenNull() {
        service.saveApplied(appointment.getId(), List.of(10L, 11L));

        assertEquals(List.of(10L, 11L), service.saveApproved(appointment, null));
    }

    @Test
    @DisplayName("审批清空全部门点会被拒绝（至少要保留一个）")
    void approveEmptyRejected() {
        service.saveApplied(appointment.getId(), List.of(10L));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.saveApproved(appointment, List.of()));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("历史单据没有门点集合时按 gate_id → 默认门点兜底")
    void legacyFallback() {
        appointment.setGateId(12L);
        assertEquals(List.of(12L), service.approvedGateIds(appointment));

        appointment.setGateId(null);
        assertTrue(!service.approvedGateIds(appointment).isEmpty(), "至少要给出默认门点");
    }
}
