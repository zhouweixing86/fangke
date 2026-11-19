package com.fangke.visitor.employee.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.employee.service.EmployeeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 上传预签名（docs/11 §5.2）。
 *
 * <p>P1/M2 只支持 {@code EMP_FACE}：前端拿到 PUT URL 直传 MinIO，再调
 * {@code /api/admin/employee/{id}/face/by-key} 绑定，避免照片经应用服务器中转。
 */
@RestController
@RequestMapping("/api/admin/upload")
public class UploadController {

    private final EmployeeService employeeService;

    public UploadController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @PostMapping("/policy")
    public ApiResponse<Map<String, Object>> policy(@RequestBody Map<String, Object> body) {
        String bizType = body == null ? null : String.valueOf(body.getOrDefault("bizType", ""));
        if (!"EMP_FACE".equals(bizType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的业务类型：" + bizType);
        }
        Object employeeId = body.get("employeeId");
        if (!(employeeId instanceof Number n)) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "EMP_FACE 需要 employeeId");
        }
        String fileName = body.get("fileName") == null ? null : String.valueOf(body.get("fileName"));
        return ApiResponse.ok(employeeService.presignUpload(n.longValue(), fileName));
    }
}
