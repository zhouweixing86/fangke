package com.fangke.visitor.dispatch.repository;

import com.fangke.visitor.dispatch.model.AccessGrant;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccessGrantRepository {

    Optional<AccessGrant> findByAppointmentId(Long appointmentId);

    /** 多门点（C-64）：同一预约单下每个批准门点一行授权。 */
    Optional<AccessGrant> findByAppointmentIdAndGate(Long appointmentId, Long gateId);

    /** 撤销时要覆盖全部门点。 */
    List<AccessGrant> findAllByAppointmentId(Long appointmentId);

    /** 员工授权（C-66）：按主体查全部门点授权。 */
    List<AccessGrant> findBySubject(int subjectType, Long subjectId);

    /** 员工授权：同一员工同一门点只保留一条。 */
    Optional<AccessGrant> findBySubjectAndGate(int subjectType, Long subjectId, Long gateId);

    /** 按人员编号取最近一条授权（批次重试时反查预约单）。 */
    Optional<AccessGrant> findLatestByPersonNo(String personNo);

    /** 管理端授权查询。 */
    List<AccessGrant> search(Integer subjectType, Integer status, int offset, int limit);

    long count(Integer subjectType, Integer status);

    long insert(AccessGrant grant);

    void updateStatus(Long id, int status);

    void revoke(Long id, Long operatorId);

    /** 已过期且超过保留期的授权（JOB-05 清理用）。 */
    List<AccessGrant> findExpiredBefore(LocalDateTime before, int limit);
}
