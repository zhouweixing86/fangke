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

    public record CodeResult(String phoneMask, int ttlSeconds, String mockCode, int resendAfterSeconds) {
    }

    public record LoginResult(String token, String role, Long subjectId, String name,
                              String phoneMask, long expiresInSeconds) {
    }

    /** 微信一键登录结果：bound=false 表示该微信还没绑定访客档案（首次预约后自动绑定）。 */
    public record WxLoginResult(String token, String role, Long subjectId, String name,
                                String openid, boolean bound, long expiresInSeconds) {
    }

    private record CodeEntry(String code, Instant createdAt, Instant expireAt, int attempts) {
    }

    private final EmployeeRepository employeeRepository;
    private final VisitorRepository visitorRepository;
    private final CryptoService crypto;
    private final JwtService jwtService;
    private final AppProperties props;
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    public AppAuthService(EmployeeRepository employeeRepository,
                          VisitorRepository visitorRepository,
                          CryptoService crypto,
                          JwtService jwtService,
                          AppProperties props) {
        this.employeeRepository = employeeRepository;
        this.visitorRepository = visitorRepository;
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
        var employee = employeeRepository.findByPhoneHmac(hmac);
        if (employee.isPresent()) {
            Employee e = employee.get();
            if (e.getStatus() != Employee.STATUS_ACTIVE) {
                throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该员工账号已停用");
            }
            return issue(ROLE_EMPLOYEE, e.getId(), e.getName(), CryptoService.maskPhone(normalized));
        }
        var visitor = visitorRepository.findByPhoneHmac(hmac);
        if (visitor.isPresent()) {
            Visitor v = visitor.get();
            return issue(ROLE_VISITOR, v.getId(), v.getName(), CryptoService.maskPhone(normalized));
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID,
                "该手机号还没有登记记录，请先在「访客预约」里提交一次预约");
    }

    private LoginResult issue(String role, Long subjectId, String name, String phoneMask) {
        String token = jwtService.issue(subjectId, phoneMask, List.of(role));
        log.info("App 登录成功 role={} subjectId={} phone={}", role, subjectId, phoneMask);
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
        String openid = resolveOpenid(code);
        var visitor = visitorRepository.findByWxOpenid(openid);
        if (visitor.isPresent()) {
            Visitor v = visitor.get();
            String token = jwtService.issue(v.getId(), v.getPhoneMask(), List.of(ROLE_VISITOR));
            log.info("微信一键登录（已绑定） openid={} visitorId={} name={}", openid, v.getId(), v.getName());
            return new WxLoginResult(token, ROLE_VISITOR, v.getId(), v.getName(), openid, true,
                    jwtService.ttlSeconds());
        }
        String token = jwtService.issue(0L, "微信用户", List.of(ROLE_VISITOR));
        log.info("微信一键登录（未绑定，待首次预约） openid={}", openid);
        return new WxLoginResult(token, ROLE_VISITOR, 0L, "微信访客", openid, false,
                jwtService.ttlSeconds());
    }

    /** mock 模式把 code 当 openid，方便没有小程序账号时联调。 */
    private String resolveOpenid(String code) {
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
                throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR,
                        "微信登录失败：" + node.path("errmsg").asText(body));
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
