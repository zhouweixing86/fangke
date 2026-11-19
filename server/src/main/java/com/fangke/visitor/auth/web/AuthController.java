package com.fangke.visitor.auth.web;

import com.fangke.visitor.auth.service.AuthService;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录接口（无需鉴权）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@RequestBody(required = false) LoginRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "用户名和密码不能为空");
        }
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }

    /** 无状态 JWT，后端无需登出；保留接口便于前端统一处理。 */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout() {
        return ApiResponse.ok(Map.of("ok", true));
    }
}
