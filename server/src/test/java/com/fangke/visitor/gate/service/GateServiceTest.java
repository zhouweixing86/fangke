package com.fangke.visitor.gate.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.repository.InMemoryGateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门点解析口径：多门点部署后不能再依赖写死的 gate_id=1。
 */
class GateServiceTest {

    private InMemoryGateRepository repo;
    private AppProperties props;
    private GateService service;

    @BeforeEach
    void setUp() {
        repo = InMemoryGateRepository.withUnassigned();
        props = new AppProperties();
        props.getGate().setDefaultId(1L);
        service = new GateService(repo, props);
    }

    @Test
    @DisplayName("待分配门点不能作为授权门点（1002）")
    void unassignedCannotBeAuthorized() {
        Long unassignedId = service.unassignedGateId();
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requireSelectable(unassignedId));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());

        assertFalse(service.listSelectable().stream().anyMatch(Gate::unassigned),
                "选门列表必须排除「待分配」");
    }

    @Test
    @DisplayName("默认门点配置失效时回落到第一个可选门点")
    void defaultGateFallsBack() {
        // default-id=1 但库里只有待分配，说明配置指向的门点不存在
        long east = service.create("EAST_GATE", "东门", Gate.TYPE_SIDE, 1, null);

        assertEquals(east, service.defaultGateId());
    }

    @Test
    @DisplayName("停用的门点不能选，也不能挂设备")
    void disabledGateRejected() {
        long gateId = service.create("WEST_GATE", "西门", Gate.TYPE_SIDE, 2, null);
        service.setStatus(gateId, Gate.STATUS_DISABLED);

        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(),
                assertThrows(BusinessException.class, () -> service.requireSelectable(gateId)).getCode());
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(),
                assertThrows(BusinessException.class, () -> service.requireAssignable(gateId)).getCode());
    }

    @Test
    @DisplayName("设备可以挂到「待分配」，但普通门点才允许停用校验")
    void deviceCanSitInUnassigned() {
        Long unassignedId = service.unassignedGateId();
        assertEquals(unassignedId, service.requireAssignable(unassignedId).id());
    }

    @Test
    @DisplayName("门点编码唯一且保留编码不可占用")
    void codeRules() {
        service.create("gate_a", "A 门", Gate.TYPE_MAIN, 0, null);

        BusinessException dup = assertThrows(BusinessException.class,
                () -> service.create("GATE_A", "重复", Gate.TYPE_MAIN, 0, null));
        assertEquals(ErrorCode.PARAM_INVALID.code(), dup.getCode());

        BusinessException reserved = assertThrows(BusinessException.class,
                () -> service.create(Gate.CODE_UNASSIGNED, "占用保留码", Gate.TYPE_MAIN, 0, null));
        assertEquals(ErrorCode.PARAM_INVALID.code(), reserved.getCode());

        List<Gate> all = service.listAll();
        assertTrue(all.stream().anyMatch(g -> "GATE_A".equals(g.gateCode())), "编码统一转大写");
    }

    @Test
    @DisplayName("「待分配」不能改成普通门点，也不能停用")
    void unassignedIsReserved() {
        Long unassignedId = service.unassignedGateId();

        assertEquals(ErrorCode.PARAM_INVALID.code(),
                assertThrows(BusinessException.class,
                        () -> service.update(unassignedId, "待分配", Gate.TYPE_MAIN, 999, null)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(),
                assertThrows(BusinessException.class,
                        () -> service.setStatus(unassignedId, Gate.STATUS_DISABLED)).getCode());
    }
}
