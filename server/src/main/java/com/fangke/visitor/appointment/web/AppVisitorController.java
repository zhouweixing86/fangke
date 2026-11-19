package com.fangke.visitor.appointment.web;

import com.fangke.visitor.appointment.dto.AppointmentCreateRequest;
import com.fangke.visitor.appointment.dto.AppointmentView;
import com.fangke.visitor.appointment.dto.ConsentRequest;
import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.service.AppointmentService;
import com.fangke.visitor.appointment.service.VisitorService;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.employee.service.EmployeeValidator;
import com.fangke.visitor.security.AppAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 访客端预约接口（docs/11 §2）。
 *
 * <p><b>鉴权</b>：{@code /api/app/**} 由 {@link AppAuthInterceptor} 校验 App token；
 * 公开接口只有 登录/验证码、隐私同意、现场自助登记，以及首次提交预约（POST /appointment）。
 * 读/撤回/取码都要求登录，并做归属校验。
 */
@RestController
@RequestMapping("/api/app")
public class AppVisitorController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppointmentService appointmentService;
    private final VisitorService visitorService;
    private final AppointmentRepository appointmentRepository;

    public AppVisitorController(AppointmentService appointmentService, VisitorService visitorService,
                                AppointmentRepository appointmentRepository) {
        this.appointmentService = appointmentService;
        this.visitorService = visitorService;
        this.appointmentRepository = appointmentRepository;
    }

    /** 隐私告知同意（首次使用必须单独同意）。 */
    @PostMapping("/privacy/agree")
    public ApiResponse<Map<String, Object>> agree(@RequestBody ConsentRequest request, HttpServletRequest http) {
        if (request == null || request.idCard() == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "身份证号不能为空");
        }
        String idCard = EmployeeValidator.validateIdCard(request.idCard());
        var visitor = visitorService.resolveOrCreate(
                EmployeeValidator.requireText(request.name(), "姓名"),
                idCard,
                EmployeeValidator.validatePhone(request.phone()),
                request.company());
        visitorService.agree(visitor.getId(), request.consentType() == null ? 1 : request.consentType(),
                request.version() == null ? "v1.0" : request.version(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
        return ApiResponse.ok(Map.of(
                "agreed", true,
                "visitorId", visitor.getId(),
                "agreedAt", DATETIME.format(LocalDateTime.now())));
    }

    /** 提交预约（访客自约）。 */
    @PostMapping("/appointment")
    public ApiResponse<AppointmentView> create(@RequestBody AppointmentCreateRequest request,
                                               HttpServletRequest http) {
        return ApiResponse.ok(appointmentService.create(request, 1,
                http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    /** 员工代邀（一级自动通过，有效期上限 7 天）。员工身份取自 App token。 */
    @PostMapping("/appointment/invite")
    public ApiResponse<AppointmentView> invite(@RequestBody AppointmentCreateRequest request,
                                               @RequestParam(required = false) Long employeeId,
                                               HttpServletRequest http) {
        Long inviter = AppAuthInterceptor.isEmployee(http)
                ? AppAuthInterceptor.subjectId(http)
                : employeeId;
        if (inviter == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先以员工身份登录");
        }
        return ApiResponse.ok(appointmentService.createInvite(request, inviter,
                http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    /** 现场自助登记（扫码进入，source=3）。 */
    @PostMapping("/onsite/appointment")
    public ApiResponse<AppointmentView> onsite(@RequestBody AppointmentCreateRequest request,
                                               HttpServletRequest http) {
        return ApiResponse.ok(appointmentService.createOnsite(request,
                http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    /** 我的预约（按 App token 里的访客身份查询，不再接受 visitorId 入参）。 */
    @GetMapping("/appointment")
    public ApiResponse<PageResult<AppointmentView>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest http) {
        Long visitorId = AppAuthInterceptor.requireVisitor(http);
        return ApiResponse.ok(appointmentService.listByVisitor(visitorId, status, page, size));
    }

    @GetMapping("/appointment/{appointmentNo}")
    public ApiResponse<AppointmentView> detail(@PathVariable String appointmentNo,
                                               HttpServletRequest http) {
        requireOwned(appointmentNo, http);
        return ApiResponse.ok(appointmentService.detail(appointmentNo));
    }

    /** 撤回申请（仅待审批状态可撤回）。 */
    @PostMapping("/appointment/{appointmentNo}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String appointmentNo,
                                    @RequestBody(required = false) Map<String, String> body,
                                    HttpServletRequest http) {
        requireOwned(appointmentNo, http);
        appointmentService.cancel(appointmentNo, body == null ? null : body.get("reason"));
        return ApiResponse.ok();
    }

    /** 归属校验：访客只能看自己的单，员工只能看“被访人是我”的单。 */
    private Appointment requireOwned(String appointmentNo, HttpServletRequest request) {
        Appointment appointment = appointmentRepository.findByNo(appointmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "预约单不存在：" + appointmentNo));
        Long subjectId = AppAuthInterceptor.subjectId(request);
        boolean owner = java.util.Objects.equals(appointment.getVisitorId(), subjectId)
                || java.util.Objects.equals(appointment.getVisitEmployeeId(), subjectId);
        if (!owner) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能查看自己的预约");
        }
        return appointment;
    }
}
