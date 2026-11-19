package com.fangke.visitor.gate.repository;

import com.fangke.visitor.gate.model.Gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用内存门点仓储：{@link #withUnassigned()} 会带一个「待分配」门点，
 * 与 V11 迁移后的真实库一致。
 */
public class InMemoryGateRepository implements GateRepository {

    private final Map<Long, Gate> gates = new LinkedHashMap<>();
    private long seq = 1L;

    public static InMemoryGateRepository withUnassigned() {
        InMemoryGateRepository repo = new InMemoryGateRepository();
        repo.put(new Gate(null, Gate.CODE_UNASSIGNED, "待分配", Gate.TYPE_UNASSIGNED,
                Gate.STATUS_ENABLED, 999, null));
        return repo;
    }

    public InMemoryGateRepository put(Gate gate) {
        long id = gate.id() == null ? nextId() : gate.id();
        gates.put(id, new Gate(id, gate.gateCode(), gate.gateName(), gate.gateType(),
                gate.status(), gate.sortNo(), gate.remark()));
        return this;
    }

    private long nextId() {
        while (gates.containsKey(seq)) {
            seq++;
        }
        return seq++;
    }

    @Override
    public List<Gate> findAll() {
        List<Gate> list = new ArrayList<>(gates.values());
        list.sort((a, b) -> a.sortNo() != b.sortNo()
                ? Integer.compare(a.sortNo(), b.sortNo())
                : Long.compare(a.id(), b.id()));
        return list;
    }

    @Override
    public Optional<Gate> findById(Long id) {
        return Optional.ofNullable(gates.get(id));
    }

    @Override
    public Optional<Gate> findByCode(String gateCode) {
        return gates.values().stream()
                .filter(g -> g.gateCode().equalsIgnoreCase(gateCode))
                .findFirst();
    }

    @Override
    public long insert(Gate gate) {
        long id = nextId();
        gates.put(id, new Gate(id, gate.gateCode(), gate.gateName(), gate.gateType(),
                gate.status(), gate.sortNo(), gate.remark()));
        return id;
    }

    @Override
    public void update(Gate gate) {
        if (gates.containsKey(gate.id())) {
            gates.put(gate.id(), gate);
        }
    }

    @Override
    public void updateStatus(Long id, int status) {
        Gate existing = gates.get(id);
        if (existing != null) {
            gates.put(id, new Gate(existing.id(), existing.gateCode(), existing.gateName(),
                    existing.gateType(), status, existing.sortNo(), existing.remark()));
        }
    }

    @Override
    public boolean existsCode(String gateCode, Long excludeId) {
        return gates.values().stream()
                .anyMatch(g -> g.gateCode().equalsIgnoreCase(gateCode)
                        && (excludeId == null || !excludeId.equals(g.id())));
    }
}
