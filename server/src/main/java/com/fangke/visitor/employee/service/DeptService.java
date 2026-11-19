package com.fangke.visitor.employee.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.employee.repository.DeptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 部门维护：员工档案与导入都从这里取部门，部门必须能在管理端自助维护。
 *
 * <p>删除用逻辑删除（`deleted_at`），历史员工仍能带出部门名；
 * 有员工或有子部门时不允许删除，必须先转移。
 */
@Service
public class DeptService {

    private static final Logger log = LoggerFactory.getLogger(DeptService.class);

    private final DeptRepository deptRepository;

    public DeptService(DeptRepository deptRepository) {
        this.deptRepository = deptRepository;
    }

    public List<DeptRepository.Dept> list() {
        return deptRepository.findAll();
    }

    public DeptRepository.Dept require(Long id) {
        return deptRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "部门不存在：" + id));
    }

    @Transactional
    public long create(String deptCode, String deptName, Long parentId, Integer sortNo) {
        String code = normalizeCode(deptCode);
        if (deptRepository.existsCode(code, null)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "部门编码已存在：" + code);
        }
        Long parent = normalizeParent(parentId, null);
        long id = deptRepository.insert(code, requireName(deptName), parent, sortNo == null ? 0 : sortNo);
        log.info("新增部门 id={} code={} name={}", id, code, deptName);
        return id;
    }

    @Transactional
    public void update(Long id, String deptName, Long parentId, Integer sortNo) {
        DeptRepository.Dept existing = require(id);
        Long parent = normalizeParent(parentId, id);
        int sort = sortNo == null ? existing.sortNo() : sortNo;
        deptRepository.update(id, requireName(deptName), parent, sort);
        log.info("修改部门 id={} name={} parent={}", id, deptName, parent);
    }

    @Transactional
    public void delete(Long id) {
        DeptRepository.Dept dept = require(id);
        if (dept.employeeCount() > 0) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "该部门下还有 " + dept.employeeCount() + " 名员工，请先把员工调整到其他部门");
        }
        boolean hasChild = deptRepository.findAll().stream()
                .anyMatch(d -> id.equals(d.parentId()));
        if (hasChild) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "请先删除该部门下的子部门");
        }
        deptRepository.softDelete(id);
        log.info("删除部门 id={} code={}", id, dept.code());
    }

    /* ---------------- 校验 ---------------- */

    private String normalizeCode(String deptCode) {
        if (deptCode == null || deptCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "部门编码不能为空");
        }
        String code = deptCode.trim().toUpperCase(Locale.ROOT);
        if (code.length() > 32) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "部门编码最长 32 位");
        }
        if (!code.matches("[A-Z0-9_]+")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "部门编码只能由字母、数字、下划线组成");
        }
        return code;
    }

    private String requireName(String deptName) {
        String name = deptName == null ? "" : deptName.trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "部门名称不能为空");
        }
        if (name.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "部门名称最长 64 字");
        }
        return name;
    }

    private Long normalizeParent(Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return null;
        }
        if (parentId.equals(selfId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上级部门不能是自己");
        }
        if (deptRepository.findById(parentId).isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上级部门不存在：" + parentId);
        }
        return parentId;
    }
}
