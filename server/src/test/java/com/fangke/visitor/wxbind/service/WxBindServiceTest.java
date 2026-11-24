package com.fangke.visitor.wxbind.service;

import com.fangke.visitor.app.auth.service.AppAuthService;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.wxbind.repository.WxBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信角色绑定（C-76）：扫码令牌校验 + 绑定后授予角色。
 */
class WxBindServiceTest {

    private WxBindingRepository repository;
    private EmployeeRepository employeeRepository;
    private AppAuthService appAuthService;
    private WxBindService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");
        repository = mock(WxBindingRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        appAuthService = mock(AppAuthService.class);
        service = new WxBindService(repository, employeeRepository, new CryptoService(props), appAuthService);
    }

    @Test
    @DisplayName("失效二维码：预览直接拒绝（4001）")
    void previewInvalidToken() {
        when(repository.findByToken(anyString())).thenReturn(Optional.empty());
        BusinessException e = assertThrows(BusinessException.class, () -> service.preview("bad-token"));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("扫码绑定：写入绑定 + 授予角色，返回新 token 与角色")
    void bindGrantsRole() {
        when(repository.findByToken("t1")).thenReturn(Optional.of(
                new WxBindingRepository.QrRole(9L, 5L, "APPROVER", "审批", "t1")));
        when(employeeRepository.findByPhoneHmacAnyStatus(anyString())).thenReturn(Optional.empty());
        when(appAuthService.resolveOpenid("code-1")).thenReturn("mock-code-1");
        when(repository.upsertBinding(eq("mock-code-1"), eq("王审批"), any(), anyString(), anyString(), any()))
                .thenReturn(3L);
        when(repository.roleCodes(3L)).thenReturn(List.of("APPROVER"));
        AppAuthService.WxLoginResult login = new AppAuthService.WxLoginResult(
                "token-x", "APP_VISITOR", 0L, "王审批", "mock-code-1", false, 3600, true,
                List.of("APPROVER"), true, 3L);
        when(appAuthService.loginByOpenid("mock-code-1")).thenReturn(login);

        WxBindService.BindResult result = service.bind("t1", "code-1", "王审批", "13700137001");

        assertEquals("审批", result.roleName());
        assertEquals(List.of("APPROVER"), result.roles());
        assertEquals("token-x", result.login().token());
        verify(repository).addRole(3L, 5L, "APPROVER", 9L);
        verify(appAuthService).loginByOpenid("mock-code-1");
    }

    @Test
    @DisplayName("手机号非法直接拒绝，不落库")
    void bindRejectsBadPhone() {
        when(repository.findByToken("t1")).thenReturn(Optional.of(
                new WxBindingRepository.QrRole(9L, 5L, "APPROVER", "审批", "t1")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.bind("t1", "code-1", "王审批", "123"));
        assertEquals(ErrorCode.PHONE_INVALID.code(), e.getCode());
    }
}
