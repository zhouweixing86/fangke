package com.fangke.visitor.employee.repository;

import com.fangke.visitor.employee.model.EmployeeFace;

import java.util.List;
import java.util.Optional;

public interface EmployeeFaceRepository {

    long insert(EmployeeFace face);

    /** 替换照片时把旧记录置为非当前（BR-E-005 留痕）。 */
    void deactivateOthers(Long employeeId);

    Optional<EmployeeFace> findCurrent(Long employeeId);

    List<EmployeeFace> listByEmployee(Long employeeId);
}
