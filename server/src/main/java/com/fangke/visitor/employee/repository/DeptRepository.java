package com.fangke.visitor.employee.repository;

import java.util.List;
import java.util.Optional;

public interface DeptRepository {

    /**
     * @param employeeCount 该部门下的员工数（删除前要先转移，避免员工挂着不存在的部门）
     */
    record Dept(Long id, String code, String name, Long parentId, int sortNo, long employeeCount) {
    }

    List<Dept> findAll();

    Optional<Dept> findById(Long id);

    Long findIdByCode(String code);

    long insert(String code, String name, Long parentId, int sortNo);

    void update(Long id, String name, Long parentId, int sortNo);

    /** 逻辑删除：历史员工记录仍能读到部门名，不做物理删除。 */
    void softDelete(Long id);

    boolean existsCode(String code, Long excludeId);
}
