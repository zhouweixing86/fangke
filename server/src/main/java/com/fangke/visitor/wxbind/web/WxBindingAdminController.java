package com.fangke.visitor.wxbind.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.wxbind.repository.WxBindingRepository;
import com.fangke.visitor.wxbind.service.WxBindService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端「微信身份」页面（C-77）：查看谁扫了码、绑了哪些角色，并支持撤销。
 *
 * <p>撤销单个角色 = 该微信失去这个身份；删除整条绑定 = 该微信回到普通访客（全部身份清空）。
 */
@RestController
@RequestMapping("/api/admin/wx-bindings")
public class WxBindingAdminController {

    private final WxBindService wxBindService;

    public WxBindingAdminController(WxBindService wxBindService) {
        this.wxBindService = wxBindService;
    }

    @GetMapping
    public ApiResponse<List<WxBindingRepository.BindingRow>> list(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(wxBindService.listBindings(keyword));
    }

    @DeleteMapping("/{bindingId}/roles/{roleCode}")
    public ApiResponse<Void> revokeRole(@PathVariable long bindingId, @PathVariable String roleCode) {
        wxBindService.revokeRole(bindingId, roleCode);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{bindingId}")
    public ApiResponse<Void> revokeBinding(@PathVariable long bindingId) {
        wxBindService.revokeBinding(bindingId);
        return ApiResponse.ok();
    }
}
