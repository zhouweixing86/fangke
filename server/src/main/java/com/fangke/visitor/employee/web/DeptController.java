package com.fangke.visitor.employee.web;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.employee.repository.DeptRepository;
import com.fangke.visitor.employee.service.DeptService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理（docs/11 §5.1）：员工新增/导入的部门下拉就取这里。
 */
@RestController
@RequestMapping("/api/admin/dept")
public class DeptController {

    private final DeptService deptService;

    public DeptController(DeptService deptService) {
        this.deptService = deptService;
    }

    @GetMapping
    public ApiResponse<List<DeptRepository.Dept>> list() {
        return ApiResponse.ok(deptService.list());
    }

    @PostMapping
    @AuditLog(module = "DEPT", action = "CREATE")
    public ApiResponse<DeptRepository.Dept> create(@RequestBody DeptRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求体不能为空");
        }
        long id = deptService.create(request.deptCode(), request.deptName(),
                request.parentId(), request.sortNo());
        return ApiResponse.ok(deptService.require(id));
    }

    @PutMapping("/{id}")
    @AuditLog(module = "DEPT", action = "UPDATE")
    public ApiResponse<DeptRepository.Dept> update(@PathVariable Long id, @RequestBody DeptRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求体不能为空");
        }
        deptService.update(id, request.deptName(), request.parentId(), request.sortNo());
        return ApiResponse.ok(deptService.require(id));
    }

    @DeleteMapping("/{id}")
    @AuditLog(module = "DEPT", action = "DELETE")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        deptService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * @param parentId null 或 0 表示顶级部门
     */
    public record DeptRequest(String deptCode, String deptName, Long parentId, Integer sortNo) {
    }
}
