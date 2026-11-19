package com.fangke.visitor.app.auth.service;

import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 访客端/员工端登录：验证码、身份判定、失败原因。
 */
class AppAuthServiceTest {

    private static final String PHONE = "13800138000";

    private AppProperties props;
    private CryptoService crypto;
    private EmployeeRepository employeeRepo;
    private VisitorRepository visitorRepo;
    private AppAuthService service;
    private String phoneHmac;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");
        props.getJwt().setSecret("unit-jwt-secret-for-tests-1234567890");
        crypto = new CryptoService(props);
        phoneHmac = crypto.hmac(PHONE);

        employeeRepo = mock(EmployeeRepository.class);
        visitorRepo = mock(VisitorRepository.class);
        service = new AppAuthService(employeeRepo, visitorRepo, crypto, new JwtService(props), props);
    }

    private String code() {
        return service.sendCode(PHONE).mockCode();
    }

    @Test
    @DisplayName("开发模式：回显 6 位验证码，且 60 秒内不允许重复获取")
    void sendCodeInMockMode() {
        AppAuthService.CodeResult result = service.sendCode(PHONE);

        assertNotNull(result.mockCode());
        assertEquals(6, result.mockCode().length());
        assertTrue(result.phoneMask().startsWith("138"));
        assertEquals(60, result.resendAfterSeconds());

        BusinessException again = assertThrows(BusinessException.class, () -> service.sendCode(PHONE));
        assertTrue(again.getMessage().contains("过于频繁"));
    }

    @Test
    @DisplayName("生产模式（未接短信网关）：明确报错，不假装已发送")
    void sendCodeWithoutGatewayFails() {
        props.getApp().setSmsMock(false);

        BusinessException e = assertThrows(BusinessException.class, () -> service.sendCode(PHONE));
        assertEquals(ErrorCode.THIRD_PARTY_ERROR.code(), e.getCode());
    }

    @Test
    @DisplayName("手机号格式错误：拒绝")
    void sendCodeValidatesPhone() {
        assertEquals(ErrorCode.PHONE_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.sendCode("12345")).getCode());
    }

    @Test
    @DisplayName("验证码不正确：401，且不签发 token")
    void loginWithWrongCode() {
        code();
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.login(PHONE, "000000"));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), e.getCode());
        assertTrue(e.getMessage().contains("不正确"));
    }

    @Test
    @DisplayName("未获取验证码就登录：明确提示")
    void loginWithoutCode() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.login(PHONE, "123456"));
        assertTrue(e.getMessage().contains("请先获取验证码"));
    }

    @Test
    @DisplayName("员工手机号登录：角色 APP_EMPLOYEE，token 里带 uid 与角色")
    void loginAsEmployee() {
        Employee employee = new Employee();
        employee.setId(7L);
        employee.setName("张主管");
        employee.setStatus(Employee.STATUS_ACTIVE);
        when(employeeRepo.findByPhoneHmac(phoneHmac)).thenReturn(Optional.of(employee));

        String mock = code();
        AppAuthService.LoginResult result = service.login(PHONE, mock);

        assertEquals(AppAuthService.ROLE_EMPLOYEE, result.role());
        assertEquals(7L, result.subjectId());
        assertEquals("张主管", result.name());
        assertNotNull(result.token());
        assertTrue(result.token().split("\\.").length == 3);
    }

    @Test
    @DisplayName("员工已停用：禁止登录")
    void loginAsDisabledEmployee() {
        Employee employee = new Employee();
        employee.setId(7L);
        employee.setName("已离职");
        employee.setStatus(3);
        when(employeeRepo.findByPhoneHmac(phoneHmac)).thenReturn(Optional.of(employee));

        String mock = code();
        assertEquals(ErrorCode.ACCOUNT_DISABLED.code(), assertThrows(BusinessException.class,
                () -> service.login(PHONE, mock)).getCode());
    }

    @Test
    @DisplayName("访客手机号登录：角色 APP_VISITOR")
    void loginAsVisitor() {
        when(employeeRepo.findByPhoneHmac(anyString())).thenReturn(Optional.empty());
        Visitor visitor = new Visitor();
        visitor.setId(9L);
        visitor.setName("访客甲");
        when(visitorRepo.findByPhoneHmac(phoneHmac)).thenReturn(Optional.of(visitor));

        String mock = code();
        AppAuthService.LoginResult result = service.login(PHONE, mock);

        assertEquals(AppAuthService.ROLE_VISITOR, result.role());
        assertEquals(9L, result.subjectId());
    }

    @Test
    @DisplayName("手机号未登记：提示先完成预约登记")
    void loginUnknownPhone() {
        when(employeeRepo.findByPhoneHmac(anyString())).thenReturn(Optional.empty());
        when(visitorRepo.findByPhoneHmac(anyString())).thenReturn(Optional.empty());

        String mock = code();
        BusinessException e = assertThrows(BusinessException.class, () -> service.login(PHONE, mock));
        assertTrue(e.getMessage().contains("还没有登记"));
    }

    @Test
    @DisplayName("验证码用一次即失效")
    void codeIsSingleUse() {
        Employee employee = new Employee();
        employee.setId(7L);
        employee.setName("张主管");
        employee.setStatus(Employee.STATUS_ACTIVE);
        when(employeeRepo.findByPhoneHmac(phoneHmac)).thenReturn(Optional.of(employee));

        String mock = code();
        assertNotNull(service.login(PHONE, mock).token());
        assertEquals(ErrorCode.UNAUTHORIZED.code(), assertThrows(BusinessException.class,
                () -> service.login(PHONE, mock)).getCode());
    }
}
