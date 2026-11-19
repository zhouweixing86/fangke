package com.fangke.visitor.appointment.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentGateRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.service.GateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 预约门点集合（C-64）：访客申请集合 + 审批批准集合。
 *
 * <p>口径：
 * <ul>
 *   <li>访客可勾选多个门点，或直接申请「全部门点」；</li>
 *   <li>审批人默认继承申请集合，<b>可增可减</b>，差异留痕（两个 stage 都落库可对比）；</li>
 *   <li>「全部门点」在<b>审批通过时快照</b>成当时启用的门点，不动态跟随，
 *       避免以后新增门点时老访客自动获得通行权（权限泄漏）。</li>
 * </ul>
 */
@Service
public class AppointmentGateService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentGateService.class);

    private final AppointmentGateRepository gateRepository;
    private final GateService gateService;

    public AppointmentGateService(AppointmentGateRepository gateRepository, GateService gateService) {
        this.gateRepository = gateRepository;
        this.gateService = gateService;
    }

    /** 访客申请口径的解析结果：主门点用于列表展示，集合用于审批默认值。 */
    public record AppliedSelection(int scope, Long primaryGateId, List<Long> gateIds) {
    }

    /* ---------------- 申请 ---------------- */

    /**
     * 解析访客提交的门点：{@code scope=2} 直接展开为当前全部可选门点；
     * 否则按 gateIds（兼容旧的单值 gateId）逐个校验；都没传则回落默认门点。
     */
    public AppliedSelection resolveApplication(Long singleGateId, List<Long> gateIds, Integer scope) {
        if (scope != null && scope == Appointment.GATE_SCOPE_ALL) {
            List<Long> all = gateService.listSelectable().stream().map(Gate::id).toList();
            if (all.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "系统还没有可用门点，请联系管理员");
            }
            return new AppliedSelection(Appointment.GATE_SCOPE_ALL, all.get(0), all);
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (gateIds != null) {
            for (Long id : gateIds) {
                if (id != null) {
                    ids.add(gateService.requireSelectable(id).id());
                }
            }
        }
        if (ids.isEmpty() && singleGateId != null) {
            ids.add(gateService.requireSelectable(singleGateId).id());
        }
        if (ids.isEmpty()) {
            // 单门部署 / 老客户端：不传门点即沿用默认门点
            Long fallback = gateService.defaultGateId();
            if (fallback != null) {
                ids.add(fallback);
            }
        }
        List<Long> resolved = List.copyOf(ids);
        return new AppliedSelection(Appointment.GATE_SCOPE_SPECIFIED,
                resolved.isEmpty() ? null : resolved.get(0), resolved);
    }

    @Transactional
    public void saveApplied(Long appointmentId, List<Long> gateIds) {
        gateRepository.replaceStage(appointmentId, AppointmentGateRepository.STAGE_APPLIED, gateIds);
    }

    public List<Long> appliedGateIds(Appointment appointment) {
        if (appointment == null || appointment.getId() == null) {
            return List.of();
        }
        return gateRepository.findGateIds(appointment.getId(), AppointmentGateRepository.STAGE_APPLIED);
    }

    /* ---------------- 批准 ---------------- */

    /**
     * 审批时写入批准集合。
     *
     * @param explicitGateIds 审批人显式提交的集合；{@code null} 表示不改（沿用/快照）
     * @return 最终生效的批准集合
     */
    @Transactional
    public List<Long> saveApproved(Appointment appointment, List<Long> explicitGateIds) {
        List<Long> target;
        if (explicitGateIds == null) {
            target = approvedGateIds(appointment);
        } else {
            if (explicitGateIds.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "至少要保留一个门点");
            }
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            for (Long id : explicitGateIds) {
                if (id != null) {
                    ids.add(gateService.requireSelectable(id).id());
                }
            }
            if (ids.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "至少要保留一个门点");
            }
            target = List.copyOf(ids);
        }
        List<Long> applied = appliedGateIds(appointment);
        if (!applied.equals(target)) {
            log.info("审批调整门点 appointment={} 申请={} 批准={}",
                    appointment.getAppointmentNo(), applied, target);
        }
        gateRepository.replaceStage(appointment.getId(), AppointmentGateRepository.STAGE_APPROVED, target);
        return target;
    }

    /**
     * 运行期取最终授权门点集合（下发/核验/撤消都以此为准）。
     *
     * <p>历史单据没有 {@code appointment_gate} 记录时按顺序兜底：
     * 申请「全部」→ 当前可选门点；申请集合；{@code appointment.gate_id}；默认门点。
     */
    public List<Long> approvedGateIds(Appointment appointment) {
        if (appointment == null) {
            return List.of();
        }
        List<Long> approved = gateRepository.findGateIds(
                appointment.getId(), AppointmentGateRepository.STAGE_APPROVED);
        if (!approved.isEmpty()) {
            return approved;
        }
        return fallbackGateIds(appointment);
    }

    public List<String> namesOf(List<Long> gateIds) {
        if (gateIds == null || gateIds.isEmpty()) {
            return List.of();
        }
        return gateIds.stream().map(gateService::nameOf).filter(n -> !n.isEmpty()).toList();
    }

    /** 审批页可勾选的门点（已排除停用与「待分配」）。 */
    public List<Gate> selectableGates() {
        return gateService.listSelectable();
    }

    /* ---------------- 内部 ---------------- */

    private List<Long> fallbackGateIds(Appointment appointment) {
        if (appointment.getGateScope() == Appointment.GATE_SCOPE_ALL) {
            List<Long> all = gateService.listSelectable().stream().map(Gate::id).toList();
            if (!all.isEmpty()) {
                return all;
            }
        }
        List<Long> applied = appliedGateIds(appointment);
        if (!applied.isEmpty()) {
            return applied;
        }
        if (appointment.getGateId() != null) {
            return List.of(appointment.getGateId());
        }
        Long fallback = gateService.defaultGateId();
        return fallback == null ? List.of() : List.of(fallback);
    }
}
