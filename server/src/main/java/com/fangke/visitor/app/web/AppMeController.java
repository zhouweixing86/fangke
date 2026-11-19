package com.fangke.visitor.app.web;

import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.VisitorFaceService;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.security.AppAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 访客端/员工端「我的信息」（docs/11 §2.1）：H5/小程序首页与个人中心用。
 */
@RestController
@RequestMapping("/api/app")
public class AppMeController {

    private final EmployeeRepository employeeRepository;
    private final VisitorRepository visitorRepository;
    private final VisitorFaceService visitorFaceService;

    public AppMeController(EmployeeRepository employeeRepository, VisitorRepository visitorRepository,
                           VisitorFaceService visitorFaceService) {
        this.employeeRepository = employeeRepository;
        this.visitorRepository = visitorRepository;
        this.visitorFaceService = visitorFaceService;
    }

    /**
     * 访客自助上传人脸：采集后才能刷脸进门。
     *
     * <p>校验（≤500KB、分辨率、可解码）与压缩复用员工照片那套规则，压缩件存 MinIO。
     */
    @PostMapping("/me/face")
    public ApiResponse<VisitorFaceService.FaceResult> uploadFace(
            @RequestPart("file") MultipartFile file, HttpServletRequest request) throws IOException {
        if (!AppAuthInterceptor.isVisitor(request)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有访客可以自助上传人脸");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请选择照片");
        }
        return ApiResponse.ok(visitorFaceService.upload(
                AppAuthInterceptor.subjectId(request), file.getBytes()));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        Long subjectId = AppAuthInterceptor.subjectId(request);
        String role = (String) request.getAttribute(AppAuthInterceptor.ATTR_ROLE);
        String phone = (String) request.getAttribute(AppAuthInterceptor.ATTR_PHONE);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("role", role);
        view.put("phoneMask", phone);
        if (AppAuthInterceptor.isEmployee(request)) {
            var employee = employeeRepository.findById(subjectId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "员工不存在"));
            view.put("subjectId", employee.getId());
            view.put("name", employee.getName());
            view.put("deptName", employee.getDeptName());
            view.put("title", employee.getTitle());
            view.put("personNo", employee.getPersonNo());
        } else {
            var visitor = visitorRepository.findById(subjectId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "访客不存在"));
            view.put("subjectId", visitor.getId());
            view.put("name", visitor.getName());
            view.put("personNo", visitor.getPersonNo());
            view.put("company", visitor.getCompany());
            view.put("faceStatus", visitor.getFaceStatus());
        }
        return ApiResponse.ok(view);
    }
}
