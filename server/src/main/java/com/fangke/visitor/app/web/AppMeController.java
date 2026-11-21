package com.fangke.visitor.app.web;

import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.security.AppAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 访客端/员工端「我的信息」（docs/11 §2.1）：H5/小程序首页与个人中心用。
 *
 * <p><b>访客不支持人脸通行</b>：访客只能刷身份证进门，因此这里<em>不提供</em>人脸采集入口。
 * 一旦允许访客上传照片，设备就会用照片做刷脸比对，等于绕过"必须刷证"的要求。
 */
@RestController
@RequestMapping("/api/app")
public class AppMeController {

    private final EmployeeRepository employeeRepository;
    private final VisitorRepository visitorRepository;

    public AppMeController(EmployeeRepository employeeRepository, VisitorRepository visitorRepository) {
        this.employeeRepository = employeeRepository;
        this.visitorRepository = visitorRepository;
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
        }
        return ApiResponse.ok(view);
    }
}
