package com.fangke.visitor.app.auth.web;

import com.fangke.visitor.app.auth.service.AppAuthService;
import com.fangke.visitor.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 访客端/员工端登录（docs/11 §2.1）：手机号 + 短信验证码。
 */
@RestController
@RequestMapping("/api/app/auth")
public class AppAuthController {

    public record CodeRequest(String phone) {
    }

    public record LoginRequest(String phone, String code) {
    }

    public record WxLoginRequest(String code) {
    }

    private final AppAuthService service;

    public AppAuthController(AppAuthService service) {
        this.service = service;
    }

    @PostMapping("/code")
    public ApiResponse<Map<String, Object>> code(@RequestBody CodeRequest body) {
        AppAuthService.CodeResult result = service.sendCode(body == null ? null : body.phone());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("phoneMask", result.phoneMask());
        view.put("ttlSeconds", result.ttlSeconds());
        view.put("resendAfterSeconds", result.resendAfterSeconds());
        // 开发模式回显，生产为 null（由短信网关下发）
        view.put("mockCode", result.mockCode());
        return ApiResponse.ok(view);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest body) {
        AppAuthService.LoginResult result = service.login(
                body == null ? null : body.phone(), body == null ? null : body.code());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("token", result.token());
        view.put("role", result.role());
        view.put("subjectId", result.subjectId());
        view.put("name", result.name());
        view.put("phoneMask", result.phoneMask());
        view.put("expiresInSeconds", result.expiresInSeconds());
        return ApiResponse.ok(view);
    }

    /** 微信小程序一键登录（访客端）：前端 wx.login 拿 code 直接换 token。 */
    @PostMapping("/wx-login")
    public ApiResponse<Map<String, Object>> wxLogin(@RequestBody WxLoginRequest body) {
        AppAuthService.WxLoginResult result = service.wxLogin(body == null ? null : body.code());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("token", result.token());
        view.put("role", result.role());
        view.put("subjectId", result.subjectId());
        view.put("name", result.name());
        view.put("openid", result.openid());
        view.put("bound", result.bound());
        view.put("mock", result.mock());
        view.put("expiresInSeconds", result.expiresInSeconds());
        return ApiResponse.ok(view);
    }
}
