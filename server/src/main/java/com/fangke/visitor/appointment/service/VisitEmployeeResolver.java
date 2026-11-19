package com.fangke.visitor.appointment.service;

import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 按被访人手机号解析员工（BR-A-002）。抽成小接口，避免预约模块依赖整个员工仓储。
 */
public interface VisitEmployeeResolver {

    Optional<Long> resolveByPhoneHmac(String phoneHmac);

    @Component
    class Default implements VisitEmployeeResolver {

        private final EmployeeRepository employeeRepository;

        public Default(EmployeeRepository employeeRepository) {
            this.employeeRepository = employeeRepository;
        }

        @Override
        public Optional<Long> resolveByPhoneHmac(String phoneHmac) {
            return employeeRepository.findByPhoneHmac(phoneHmac).map(Employee::getId);
        }
    }
}
