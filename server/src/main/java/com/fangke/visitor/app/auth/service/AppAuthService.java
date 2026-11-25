package com.fangke.visitor.app.auth.service;

import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.employee.service.EmployeeValidator;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.security.JwtService;
import com.fangke.visitor.wxbind.repository.WxBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 访客端/员工端登录（手机号 + 短信验证码）。
 *
 * <p>生产必须关掉 {@code fangke.app.sms-mock} 并接入短信网关；关掉后本服务不返回验证码，
 * 由网关下发。当前未接网关时抛 {@code 5004}，避免“以为发了其实没发”。
 *
 * <p>身份判定顺序：员工（在职）→ 访客档案；都查不到说明没登记过，提示先完成预约登记。
 */
@Service
public class AppAuthService {

    private static final Logger log = LoggerFactory.getLogger(AppAuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);

    public static final String ROLE_EMPLOYEE = "APP_EMPLOYEE";
    public static final String ROLE_VISITOR = "APP_VISITOR";
    /** 已绑定任意后台角色的微信用户：小程序展示「我的审批」。 */
    public static final String ROLE_APPROVER = "APP_APPROVER";

    public record CodeResult(String phoneMask, int ttlSeconds, String mockCode, int resendAfterSeconds) {
    }

    public record LoginResult(String token, String role, Long subjectId, String name,
                              String phoneMask, long expiresInSeconds) {
    }

    /**
     * 微信一键登录结果。
     *
     * @param bound 是否已绑定人员档案（false 表示首次使用，提交一次预约后自动绑定）
     * @param mock  是否 mock 模式：mock 下 openid 由 code 派生，前端需复用同一个 code 才能保持身份稳定
     * @param roles 小程序可见身份：APP_VISITOR / APP_EMPLOYEE + APP_APPROVER + 已授予的后台角色编码
     * @param approver 是否具备审批身份（扫码绑定过任意角色）
     */
    public record WxLoginResult(String token, String role, Long subjectId, String name,
                                String openid, boolean bound, long expiresInSeconds, boolean mock,
                                List<String> roleCodes, boolean approver, Long bindingId) {
    }

    private record CodeEntry(String code, Instant createdAt, Instant expireAt, int attempts) {
    }

    private final EmployeeRepository employeeRepository;
    private final VisitorRepository visitorRepository;
    private final WxBindingRepository wxBindingRepository;
    private final CryptoService crypto;
    private final JwtService jwtService;
    private final AppProperties props;
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    public AppAuthService(EmployeeRepository employeeRepository,
                          VisitorRepository visitorRepository,
                          WxBindingRepository wxBindingRepository,
                          CryptoService crypto,
                          JwtService jwtService,
                          AppProperties props) {
        this.employeeRepository = employeeRepository;
        this.visitorRepository = visitorRepository;
        this.wxBindingRepository = wxBindingRepository;
        this.crypto = crypto;
        this.jwtService = jwtService;
        this.props = props;
    }

    /** 发送（或在开发模式返回）验证码。同一手机号 60 秒内不允许重复获取。 */
    public CodeResult sendCode(String phone) {
        String normalized = EmployeeValidator.validatePhone(
                EmployeeValidator.requireText(phone, "手机号"));
        if (normalized == null) {
            throw new BusinessException(ErrorCode.PHONE_INVALID, "手机号格式不正确");
        }
        Instant now = Instant.now();
        purgeExpired(now);

        CodeEntry existing = codes.get(normalized);
        if (existing != null && Duration.between(existing.createdAt(), now).compareTo(RESEND_INTERVAL) < 0) {
            long wait = RESEND_INTERVAL.minus(Duration.between(existing.createdAt(), now)).toSeconds() + 1;
            throw new BusinessException(ErrorCode.PARAM_INVALID, "验证码发送过于频繁，请 " + wait + " 秒后再试");
        }

        int ttl = Math.max(60, props.getApp().getCodeTtlSeconds());
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        codes.put(normalized, new CodeEntry(code, now, now.plusSeconds(ttl), 0));

        String masked = CryptoService.maskPhone(normalized);
        if (!props.getApp().isSmsMock()) {
            // TODO 接入短信网关；未接入前明确失败，避免“验证码已发送”的假象
            codes.remove(normalized);
            throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR,
                    "短信网关尚未接入，请联系管理员（开发环境可开启 fangke.app.sms-mock）");
        }
        log.info("【开发模式】手机号 {} 验证码 = {}（{} 秒内有效）", masked, code, ttl);
        return new CodeResult(masked, ttl, code, (int) RESEND_INTERVAL.toSeconds());
    }

    public LoginResult login(String phone, String code) {
        String normalized = EmployeeValidator.validatePhone(
                EmployeeValidator.requireText(phone, "手机号"));
        if (normalized == null) {
            throw new BusinessException(ErrorCode.PHONE_INVALID, "手机号格式不正确");
        }
        String input = EmployeeValidator.requireText(code, "验证码");
        CodeEntry entry = codes.get(normalized);
        if (entry == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先获取验证码");
        }
        if (Instant.now().isAfter(entry.expireAt())) {
            codes.remove(normalized);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证码已过期，请重新获取");
        }
        if (entry.attempts() >= MAX_ATTEMPTS) {
            codes.remove(normalized);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证码错误次数过多，请重新获取");
        }
        if (!entry.code().equals(input)) {
            codes.put(normalized, new CodeEntry(entry.code(), entry.createdAt(), entry.expireAt(),
                    entry.attempts() + 1));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证码不正确");
        }
        codes.remove(normalized);

        String hmac = crypto.hmac(normalized);
        var employee = employeeRepository.findByPhoneHmacAnyStatus(hmac);
        if (employee.isPresent()) {
            Employee e = employee.get();
            if (e.getStatus() != Employee.STATUS_ACTIVE) {
                throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该员工账号已停用");
            }
            return issue(ROLE_EMPLOYEE, e.getId(), e.getName(), CryptoService.maskPhone(normalized), hmac);
        }
        var visitor = visitorRepository.findByPhoneHmac(hmac);
        if (visitor.isPresent()) {
            Visitor v = visitor.get();
            return issue(ROLE_VISITOR, v.getId(), v.getName(), CryptoService.maskPhone(normalized), hmac);
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID,
                "该手机号还没有登记记录，请先在「访客预约」里提交一次预约");
    }

    private LoginResult issue(String role, Long subjectId, String name, String phoneMask, String phoneHmac) {
        List<String> roles = new java.util.ArrayList<>();
        roles.add(role);
        var binding = wxBindingRepository.findByPhoneHmac(phoneHmac);
        if (binding.isPresent() && !binding.get().roleCodes().isEmpty()) {
            roles.add(ROLE_APPROVER);
            roles.addAll(binding.get().roleCodes());
        }
        String token = jwtService.issue(subjectId, phoneMask, roles, binding.map(WxBindingRepository.Binding::id).orElse(null));
        log.info("App 登录成功 role={} subjectId={} phone={} 微信角色={}", role, subjectId, phoneMask,
                binding.map(WxBindingRepository.Binding::roleCodes).orElse(List.of()));
        return new LoginResult(token, role, subjectId, name, phoneMask, jwtService.ttlSeconds());
    }

    /**
     * 微信小程序一键登录：wx.login 拿到的 code → openid → 反查访客档案。
     *
     * <p>未绑定（首次使用）时仍然发 token，但 {@code subjectId=0}：
     * 访客可以先走公开的预约提交接口，预约成功后平台把 openid 绑到该访客，
     * 下次进入即可直接看到「我的预约」。
     */
    public WxLoginResult wxLogin(String code) {
        return loginByOpenid(resolveOpenid(code));
    }

    /**
     * 按 openid 登录：已扫码绑定角色时，token 里额外带 {@code APP_APPROVER} 与后台角色编码，
     * 小程序据此显示「我的审批」，审批接口按角色匹配待办。
     */
    public WxLoginResult loginByOpenid(String openid) {
        var binding = wxBindingRepository.findByOpenid(openid).orElse(null);
        var visitor = visitorRepository.findByWxOpenid(openid);
        List<String> roleCodes = binding == null ? List.of() : binding.roleCodes();
        boolean approver = !roleCodes.isEmpty();

        String role;
        Long subjectId;
        String name;
        if (binding != null && binding.employeeId() != null) {
            role = ROLE_EMPLOYEE;
            subjectId = binding.employeeId();
            name = binding.name();
        } else if (visitor.isPresent()) {
            role = ROLE_VISITOR;
            subjectId = visitor.get().getId();
            name = visitor.get().getName();
        } else {
            role = ROLE_VISITOR;
            subjectId = 0L;
            name = binding == null ? "微信访客" : binding.name();
        }

        List<String> roles = new java.util.ArrayList<>();
        roles.add(role);
        if (approver) {
            roles.add(ROLE_APPROVER);
            roles.addAll(roleCodes);
        }
        String phoneMask = binding != null ? binding.phoneMask()
                : visitor.map(Visitor::getPhoneMask).orElse("微信用户");
        String token = jwtService.issue(subjectId, phoneMask, roles,
                binding == null ? null : binding.id());
        log.info("微信一键登录 openid={} role={} subjectId={} 角色={}", openid, role, subjectId, roles);
        return new WxLoginResult(token, role, subjectId, name, openid, subjectId > 0,
                jwtService.ttlSeconds(), props.getWx().isMock(), roleCodes, approver,
                binding == null ? null : binding.id());
    }

    /** mock 模式把 code 当 openid，方便没有小程序账号时联调。 */
    public String resolveOpenid(String code) {
        String value = EmployeeValidator.requireText(code, "微信登录凭证");
        AppProperties.Wx wx = props.getWx();
        if (wx.isMock()) {
            return "mock-" + value;
        }
        if (wx.getAppId() == null || wx.getAppId().isBlank()
                || wx.getSecret() == null || wx.getSecret().isBlank()) {
            throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR,
                    "微信小程序 appid/secret 未配置（WX_APP_ID / WX_SECRET）");
        }
        try {
            String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + wx.getAppId()
                    + "&secret=" + wx.getSecret() + "&js_code=" + value
                    + "&grant_type=authorization_code";
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8)).GET().build();
            String body = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body);
            String openid = node.path("openid").asText("");
            if (openid.isBlank()) {
                int errcode = node.path("errcode").asInt(0);
                // 40029/40163：code 无效或已被使用（一次性），提示重试即可
                String reason = (errcode == 40029 || errcode == 40163)
                        ? "微信登录凭证已失效，请重新进入小程序重试"
                        : node.path("errmsg").asText(body);
                throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR, "微信登录失败：" + reason);
            }
            return openid;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR, "微信登录失败：" + e.getMessage());
        }
    }

    private void purgeExpired(Instant now) {
        codes.entrySet().removeIf(e -> now.isAfter(e.getValue().expireAt()));
    }
}
