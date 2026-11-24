package com.fangke.visitor.wxbind.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.wxbind.service.WxBindService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序扫码绑定角色（C-76）。
 *
 * <p>这两个接口是公开的：扫码时用户还没有可信身份，绑定结果直接返回新 token
 * （{@code wxCode} 用于换 openid，mock 模式下 code 即身份）。
 */
@RestController
@RequestMapping("/api/app/bind")
public class AppBindController {

    private final WxBindService wxBindService;

    public AppBindController(WxBindService wxBindService) {
        this.wxBindService = wxBindService;
    }

    @GetMapping("/preview")
    public ApiResponse<WxBindService.PreviewView> preview(@RequestParam String token) {
        return ApiResponse.ok(wxBindService.preview(token));
    }

    public record BindRequest(String token, String wxCode, String name, String phone) {
    }

    @PostMapping
    public ApiResponse<WxBindService.BindResult> bind(@RequestBody BindRequest request) {
        return ApiResponse.ok(wxBindService.bind(request.token(), request.wxCode(),
                request.name(), request.phone()));
    }
}
