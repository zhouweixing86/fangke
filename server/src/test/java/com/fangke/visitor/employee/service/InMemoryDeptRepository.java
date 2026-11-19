package com.fangke.visitor.employee.service;

import com.fangke.visitor.employee.repository.DeptRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用内存部门仓储：支持逻辑删除，employeeCount 由用例直接设置。
 */
public class InMemoryDeptRepository implements DeptRepository {

    /** id -> {dept, employeeCount} */
    private final Map<Long, Object[]> store = new LinkedHashMap<>();
    private long seq = 1L;

    public InMemoryDeptRepository put(long id, String code, String name, Long parentId, int sortNo,
                                      long employeeCount) {
        store.put(id, new Object[]{new Dept(id, code, name, parentId, sortNo, employeeCount), employeeCount});
        if (id >= seq) {
            seq = id + 1;
        }
        return this;
    }

    @Override
    public List<Dept> findAll() {
        List<Dept> list = new ArrayList<>();
        for (Object[] row : store.values()) {
            Dept d = (Dept) row[0];
            list.add(new Dept(d.id(), d.code(), d.name(), d.parentId(), d.sortNo(), (Long) row[1]));
        }
        list.sort((a, b) -> a.sortNo() != b.sortNo()
                ? Integer.compare(a.sortNo(), b.sortNo())
                : Long.compare(a.id(), b.id()));
        return list;
    }

    @Override
    public Optional<Dept> findById(Long id) {
        Object[] row = store.get(id);
        if (row == null) {
            return Optional.empty();
        }
        Dept d = (Dept) row[0];
        return Optional.of(new Dept(d.id(), d.code(), d.name(), d.parentId(), d.sortNo(), (Long) row[1]));
    }

    @Override
    public Long findIdByCode(String code) {
        if (code == null) {
            return null;
        }
        String target = code.trim().toUpperCase(Locale.ROOT);
        return store.values().stream()
                .map(row -> (Dept) row[0])
                .filter(d -> d.code().equals(target))
                .map(Dept::id)
                .findFirst()
                .orElse(null);
    }

    @Override
    public long insert(String code, String name, Long parentId, int sortNo) {
        long id = seq++;
        store.put(id, new Object[]{new Dept(id, code, name, parentId, sortNo, 0L), 0L});
        return id;
    }

    @Override
    public void update(Long id, String name, Long parentId, int sortNo) {
        Object[] row = store.get(id);
        if (row == null) {
            return;
        }
        Dept d = (Dept) row[0];
        store.put(id, new Object[]{new Dept(id, d.code(), name, parentId, sortNo, d.employeeCount()), (Long) row[1]});
    }

    @Override
    public void softDelete(Long id) {
        store.remove(id);
    }

    @Override
    public boolean existsCode(String code, Long excludeId) {
        if (code == null) {
            return false;
        }
        String target = code.trim().toUpperCase(Locale.ROOT);
        return store.values().stream()
                .map(row -> (Dept) row[0])
                .anyMatch(d -> d.code().equals(target) && (excludeId == null || !excludeId.equals(d.id())));
    }
}
