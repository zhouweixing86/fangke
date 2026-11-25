package com.fangke.visitor.wxbind.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.wxbind.service.WxBindService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端「角色二维码」页面（C-76）：每个后台角色一个长期二维码，
 * 小程序扫码后登记姓名+手机号即可获得该角色。
 */
@RestController
@RequestMapping("/api/admin/wx-role-qr")
public class WxRoleQrAdminController {

    private final WxBindService wxBindService;

    public WxRoleQrAdminController(WxBindService wxBindService) {
        this.wxBindService = wxBindService;
    }

    @GetMapping
    public ApiResponse<List<WxBindService.RoleQrView>> list(HttpServletRequest request) {
        Long operatorId = com.fangke.visitor.security.RoleGuard.currentUserId(request);
        return ApiResponse.ok(wxBindService.listWithQr(operatorId));
    }

    @PostMapping("/{roleId}/regenerate")
    public ApiResponse<WxBindService.RoleQrView> regenerate(@PathVariable long roleId,
                                                            HttpServletRequest request) {
        Long operatorId = com.fangke.visitor.security.RoleGuard.currentUserId(request);
        return ApiResponse.ok(wxBindService.regenerate(roleId, operatorId));
    }

    /** 小程序码（C-81）：扫码直接进小程序「绑定身份」页；未配置 AppID 时 configured=false。 */
    @GetMapping("/{roleId}/wxacode")
    public ApiResponse<WxBindService.WxacodeView> wxacode(@PathVariable long roleId) {
        return ApiResponse.ok(wxBindService.wxacode(roleId));
    }
}
