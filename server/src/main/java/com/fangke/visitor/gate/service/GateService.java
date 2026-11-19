package com.fangke.visitor.gate.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.repository.GateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 门点业务：列表、维护、默认门点解析。
 *
 * <p>门点是授权的最小粒度（docs/01 BR-V-010）：预约授权到某个门点，设备挂在门点下，
 * 通行记录按门点汇总。多门点部署时不能再依赖写死的 gate_id=1。
 */
@Service
public class GateService {

    private static final Logger log = LoggerFactory.getLogger(GateService.class);

    private final GateRepository gateRepository;
    private final AppProperties props;

    public GateService(GateRepository gateRepository, AppProperties props) {
        this.gateRepository = gateRepository;
        this.props = props;
    }

    /* ---------------- 查询 ---------------- */

    public List<Gate> listAll() {
        return gateRepository.findAll();
    }

    /** 启用门点（含「待分配」）：设备分配门点时用。 */
    public List<Gate> listEnabled() {
        return gateRepository.findAll().stream().filter(Gate::enabled).toList();
    }

    /** 可作为授权门点：启用且不是「待分配」。访客选门、预约校验用。 */
    public List<Gate> listSelectable() {
        return gateRepository.findAll().stream()
                .filter(g -> g.enabled() && !g.unassigned())
                .toList();
    }

    public Optional<Gate> find(Long gateId) {
        return gateId == null ? Optional.empty() : gateRepository.findById(gateId);
    }

    public Optional<Gate> findByCode(String gateCode) {
        return gateCode == null ? Optional.empty() : gateRepository.findByCode(gateCode);
    }

    /** 展示用门点名；找不到返回空串（避免报表里冒出 null）。 */
    public String nameOf(Long gateId) {
        return find(gateId).map(Gate::gateName).orElse("");
    }

    /* ---------------- 业务校验 ---------------- */

    /** 预约/授权门点必须存在、启用、且不是「待分配」。 */
    public Gate requireSelectable(Long gateId) {
        if (gateId == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "门点不能为空");
        }
        Gate gate = gateRepository.findById(gateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "门点不存在：" + gateId));
        if (!gate.enabled()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "门点已停用：" + gate.gateName());
        }
        if (gate.unassigned()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "「待分配」不能作为授权门点");
        }
        return gate;
    }

    /** 设备挂载门点：允许「待分配」，但必须存在且启用。 */
    public Gate requireAssignable(Long gateId) {
        if (gateId == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "门点不能为空");
        }
        Gate gate = gateRepository.findById(gateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "门点不存在：" + gateId));
        if (!gate.enabled()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "门点已停用：" + gate.gateName());
        }
        return gate;
    }

    /**
     * 解析预约实际门点：没选就沿用配置的默认门点（单门部署口径），选了则校验。
     */
    public Long resolveGateId(Long requested) {
        return requested == null ? defaultGateId() : requireSelectable(requested).id();
    }

    /**
     * 默认门点：{@code fangke.gate.default-id} 优先（兼容历史单据），
     * 配置值失效（被删/停用/是待分配）时回落到第一个可选门点。
     */
    public Long defaultGateId() {
        Long configured = props.getGate().getDefaultId();
        if (configured != null && find(configured)
                .filter(g -> g.enabled() && !g.unassigned()).isPresent()) {
            return configured;
        }
        Optional<Long> fallback = listSelectable().stream().map(Gate::id).findFirst();
        if (fallback.isPresent()) {
            if (configured != null) {
                log.warn("默认门点配置 gate.default-id={} 不可用，回落到 {}", configured, fallback.get());
            }
            return fallback.get();
        }
        return configured;
    }

    /** 轮询式终端自动登记用的临时门点；没有「待分配」时退回默认门点，保证登记不失败。 */
    public Long unassignedGateId() {
        return gateRepository.findByCode(Gate.CODE_UNASSIGNED)
                .map(Gate::id)
                .orElseGet(this::defaultGateId);
    }

    /* ---------------- 维护 ---------------- */

    @Transactional
    public long create(String gateCode, String gateName, Integer gateType, Integer sortNo, String remark) {
        String code = normalizeCode(gateCode);
        if (Gate.CODE_UNASSIGNED.equals(code)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "「" + Gate.CODE_UNASSIGNED + "」是系统保留编码");
        }
        if (gateRepository.existsCode(code, null)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "门点编码已存在：" + code);
        }
        Gate gate = new Gate(null, code, requireName(gateName), requireType(gateType),
                Gate.STATUS_ENABLED, sortNo == null ? 0 : sortNo, trimToNull(remark));
        long id = gateRepository.insert(gate);
        log.info("新增门点 id={} code={} name={}", id, code, gate.gateName());
        return id;
    }

    @Transactional
    public void update(Long id, String gateName, Integer gateType, Integer sortNo, String remark) {
        Gate existing = gateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "门点不存在：" + id));
        if (existing.unassigned() && gateType != null && gateType != Gate.TYPE_UNASSIGNED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "「待分配」是系统保留门点，不能改成普通门点");
        }
        Gate updated = new Gate(id,
                existing.gateCode(),
                gateName == null || gateName.isBlank() ? existing.gateName() : requireName(gateName),
                gateType == null ? existing.gateType() : requireType(gateType, existing.unassigned()),
                existing.status(),
                sortNo == null ? existing.sortNo() : sortNo,
                remark == null ? existing.remark() : trimToNull(remark));
        gateRepository.update(updated);
        log.info("修改门点 id={} name={} type={}", id, updated.gateName(), updated.gateType());
    }

    @Transactional
    public void setStatus(Long id, int status) {
        Gate existing = gateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "门点不存在：" + id));
        if (existing.unassigned() && status != Gate.STATUS_ENABLED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "「待分配」是系统保留门点，不能停用");
        }
        if (status != Gate.STATUS_ENABLED && status != Gate.STATUS_DISABLED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "门点状态只能是 0 停用 / 1 启用");
        }
        gateRepository.updateStatus(id, status);
        log.info("门点状态变更 id={} status={}", id, status);
    }

    /* ---------------- 内部 ---------------- */

    private String normalizeCode(String gateCode) {
        if (gateCode == null || gateCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "门点编码不能为空");
        }
        String code = gateCode.trim().toUpperCase(Locale.ROOT);
        if (code.length() > 32) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "门点编码最长 32 位");
        }
        if (!code.matches("[A-Z0-9_]+")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "门点编码只能由字母、数字、下划线组成");
        }
        return code;
    }

    private String requireName(String gateName) {
        String name = gateName == null ? "" : gateName.trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "门点名称不能为空");
        }
        if (name.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "门点名称最长 64 字");
        }
        return name;
    }

    private int requireType(Integer gateType) {
        return requireType(gateType, false);
    }

    private int requireType(Integer gateType, boolean allowUnassigned) {
        int type = gateType == null ? Gate.TYPE_MAIN : gateType;
        boolean ok = type == Gate.TYPE_MAIN || type == Gate.TYPE_SIDE || type == Gate.TYPE_GARAGE
                || (allowUnassigned && type == Gate.TYPE_UNASSIGNED);
        if (!ok) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "门点类型只能是 1 大门 / 2 侧门 / 3 车库门");
        }
        return type;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
