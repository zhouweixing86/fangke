package com.fangke.visitor.employee.web;

import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.employee.dto.EmployeeRequest;
import com.fangke.visitor.employee.dto.EmployeeView;
import com.fangke.visitor.employee.dto.ImportResult;
import com.fangke.visitor.employee.model.EmployeeFace;
import com.fangke.visitor.employee.service.EmployeeDispatchService;
import com.fangke.visitor.employee.service.EmployeeService;
import com.fangke.visitor.security.AuthInterceptor;
import com.fangke.visitor.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 员工管理接口（docs/11 §5.1）。
 */
@RestController
@RequestMapping("/api/admin/employee")
public class EmployeeController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmployeeController.class);

    private final EmployeeService service;
    private final EmployeeDispatchService dispatchService;
    private final StorageService storage;

    public EmployeeController(EmployeeService service, EmployeeDispatchService dispatchService,
                              StorageService storage) {
        this.service = service;
        this.dispatchService = dispatchService;
        this.storage = storage;
    }

    @GetMapping
    public ApiResponse<PageResult<EmployeeView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<EmployeeView> detail(@PathVariable Long id) {
        return ApiResponse.ok(EmployeeView.of(service.get(id)));
    }

    @PostMapping
    @AuditLog(module = "EMPLOYEE", action = "CREATE")
    public ApiResponse<EmployeeView> create(@RequestBody EmployeeRequest request, HttpServletRequest http) {
        var employee = service.create(request, uid(http));
        // 入职即下发：建授权 + 推名单（失败只记日志，不回滚档案）
        dispatchService.syncQuietly(employee);
        return ApiResponse.ok(EmployeeView.of(employee));
    }

    @PutMapping("/{id}")
    @AuditLog(module = "EMPLOYEE", action = "UPDATE")
    public ApiResponse<EmployeeView> update(@PathVariable Long id,
                                            @RequestBody EmployeeRequest request,
                                            HttpServletRequest http) {
        var employee = service.update(id, request, uid(http));
        dispatchService.syncQuietly(employee);
        return ApiResponse.ok(EmployeeView.of(employee));
    }

    /** 停用（不物理删除），同时取消其通行资格（BR-E-007）。 */
    @DeleteMapping("/{id}")
    @AuditLog(module = "EMPLOYEE", action = "STOP")
    public ApiResponse<Void> stop(@PathVariable Long id, HttpServletRequest http) {
        var employee = service.stop(id, uid(http));
        try {
            dispatchService.revoke(employee, uid(http));
        } catch (Exception e) {
            // 设备不可达时仍然完成停用，由每天的对账兜底
        }
        return ApiResponse.ok();
    }

    /** 恢复在职：状态改回在职，并把授权与名单重新下发一遍（等价于重新新增）。 */
    @PostMapping("/{id}/enable")
    @AuditLog(module = "EMPLOYEE", action = "ENABLE")
    public ApiResponse<EmployeeView> enable(@PathVariable Long id, HttpServletRequest http) {
        var employee = service.enable(id, uid(http));
        dispatchService.syncQuietly(employee);
        return ApiResponse.ok(EmployeeView.of(employee));
    }

    /** 手动同步单个员工名单（排查"员工刷不开"时用）。 */
    @PostMapping("/{id}/sync")
    @AuditLog(module = "EMPLOYEE", action = "SYNC")
    public ApiResponse<EmployeeDispatchService.SyncResult> sync(@PathVariable Long id) {
        return ApiResponse.ok(dispatchService.sync(service.get(id)));
    }

    /** 一键全量同步：在岗员工全部下发、停用员工全部撤销。 */
    @PostMapping("/sync-all")
    @AuditLog(module = "EMPLOYEE", action = "SYNC_ALL")
    public ApiResponse<EmployeeDispatchService.SyncResult> syncAll() {
        return ApiResponse.ok(dispatchService.syncAll());
    }

    /** Excel 批量导入，返回逐行错误。 */
    @PostMapping("/import")
    @AuditLog(module = "EMPLOYEE", action = "IMPORT")
    public ApiResponse<ImportResult> importExcel(@RequestPart("file") MultipartFile file,
                                                 HttpServletRequest http) throws IOException {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail(com.fangke.visitor.common.ErrorCode.PARAM_MISSING.code(), "请上传 Excel 文件");
        }
        try (var in = file.getInputStream()) {
            ImportResult result = service.importExcel(in, uid(http));
            // 导入进来的人也要能刷脸进厂区：成功即触发一次全量同步
            if (result.success() > 0) {
                try {
                    dispatchService.syncAll();
                } catch (Exception e) {
                    log.warn("导入后同步员工名单失败：{}", e.getMessage());
                }
            }
            return ApiResponse.ok(result);
        }
    }

    /** 直接上传照片文件（表单上传）。 */
    @PostMapping("/{id}/face")
    @AuditLog(module = "EMPLOYEE", action = "UPLOAD_FACE")
    public ApiResponse<EmployeeFace> uploadFace(@PathVariable Long id,
                                                @RequestPart("file") MultipartFile file,
                                                HttpServletRequest http) throws IOException {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail(com.fangke.visitor.common.ErrorCode.PARAM_MISSING.code(), "请选择照片");
        }
        EmployeeFace face = service.bindFace(id, null, file.getBytes(), uid(http));
        // 换了照片要重新下发，设备才能刷脸
        dispatchService.syncQuietly(service.get(id));
        return ApiResponse.ok(face);
    }

    /** 前端直传后用 ossKey 绑定。 */
    @PostMapping("/{id}/face/by-key")
    @AuditLog(module = "EMPLOYEE", action = "BIND_FACE")
    public ApiResponse<EmployeeFace> bindFaceByKey(@PathVariable Long id,
                                                   @RequestBody Map<String, String> body,
                                                   HttpServletRequest http) {
        String ossKey = body == null ? null : body.get("ossKey");
        if (ossKey == null || ossKey.isBlank()) {
            return ApiResponse.fail(com.fangke.visitor.common.ErrorCode.PARAM_MISSING.code(), "ossKey 不能为空");
        }
        byte[] data = storage.getObject(ossKey);
        EmployeeFace face = service.bindFace(id, ossKey, data, uid(http));
        dispatchService.syncQuietly(service.get(id));
        return ApiResponse.ok(face);
    }

    @GetMapping("/{id}/face/history")
    public ApiResponse<List<EmployeeFace>> faceHistory(@PathVariable Long id) {
        return ApiResponse.ok(service.faceHistory(id));
    }

    /** 直传预签名（前端 PUT 到 MinIO，不经应用服务器）。 */
    @PostMapping("/{id}/face/presign")
    public ApiResponse<Map<String, Object>> presign(@PathVariable Long id,
                                                    @RequestParam(required = false) String fileName) {
        return ApiResponse.ok(service.presignUpload(id, fileName));
    }

    private Long uid(HttpServletRequest request) {
        Object uid = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return uid instanceof Long l ? l : null;
    }
}
