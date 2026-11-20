package com.fangke.visitor.employee.repository;

import com.fangke.visitor.employee.model.Employee;

import java.util.List;
import java.util.Optional;

/**
 * 员工仓储。抽成接口便于服务层单测（测试用内存实现）。
 */
public interface EmployeeRepository {

    long insert(Employee employee);

    void update(Employee employee);

    Optional<Employee> findById(Long id);

    Optional<Employee> findByEmpNo(String empNo);

    /**
     * 按人员编号找人。删除设备名单时用：授权已经撤销，不能再通过 access_grant 反查证件号。
     *
     * <p>默认返回空，方便测试替身只实现自己关心的方法。
     */
    default Optional<Employee> findByPersonNo(String personNo) {
        return Optional.empty();
    }

    /** 预约时按被访人手机号匹配员工（BR-A-002 / C-22）。 */
    Optional<Employee> findByPhoneHmac(String phoneHmac);

    boolean existsEmpNo(String empNo, Long excludeId);

    boolean existsPhoneHmac(String phoneHmac, Long excludeId);

    boolean existsIdCardHmac(String idCardHmac, Long excludeId);

    List<Employee> search(String keyword, Integer status, int offset, int limit);

    long count(String keyword, Integer status);

    void updateStatus(Long id, int status);

    void updateFaceStatus(Long id, int faceStatus);

    /** 有效期到期前 N 天待复核的员工（JOB-07）。 */
    List<Employee> findExpiringBefore(java.time.LocalDate deadline);

    /** 车辆通行（C-68）：按车牌找在岗员工（车牌是主车，可能多人相同则取最新一条）。 */
    /**
     * 车辆通行（C-68）：按车牌找在岗员工（车牌是主车，可能多人相同则取最新一条）。
     *
     * <p>默认返回空，方便测试替身只实现自己关心的方法；生产实现见 JdbcEmployeeRepository。
     */
    default java.util.Optional<Employee> findActiveByPlateNo(String plateNo) {
        return java.util.Optional.empty();
    }
}
