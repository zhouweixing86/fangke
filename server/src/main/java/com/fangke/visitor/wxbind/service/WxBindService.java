package com.fangke.visitor.wxbind.service;

import com.fangke.visitor.app.auth.service.AppAuthService;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.employee.service.EmployeeValidator;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.wxbind.repository.WxBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * 微信角色绑定（C-76）：管理端按角色出二维码，小程序扫码登记手机号+姓名后授予该角色。
 *
 * <p>一个微信（openid）可以多次扫码，从而同时拥有「员工」「审批」「领导」等多个角色；
 * 审批任务按节点配置的角色匹配，所以同一套角色既能给后台账号用，也能给微信审批人用。
 */
@Service
public class WxBindService {

    private static final Logger log = LoggerFactory.getLogger(WxBindService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 管理端列表项：二维码内容由前端拼成小程序页面链接。 */
    public record RoleQrView(long roleId, String roleCode, String roleName, String token, int bindCount) {
    }

    /** 扫码预览。 */
    public record PreviewView(String roleName, String roleCode) {
    }

    /** 绑定结果：直接返回新 token，扫码人无需再登录一次。 */
    public record BindResult(String roleName, List<String> roles, AppAuthService.WxLoginResult login) {
    }

    private final WxBindingRepository repository;
    private final EmployeeRepository employeeRepository;
    private final CryptoService crypto;
    private final AppAuthService appAuthService;
    private final WxacodeService wxacodeService;

    public WxBindService(WxBindingRepository repository, EmployeeRepository employeeRepository,
                         CryptoService crypto, AppAuthService appAuthService,
                         WxacodeService wxacodeService) {
        this.repository = repository;
        this.employeeRepository = employeeRepository;
        this.crypto = crypto;
        this.appAuthService = appAuthService;
        this.wxacodeService = wxacodeService;
    }

    /** 小程序码结果：未配置 AppID 时 configured=false，前端回退成链接二维码。 */
    public record WxacodeView(boolean configured, String imageBase64, String hint) {
    }

    /** 生成该角色的小程序码（扫了直接进小程序「绑定身份」页）。 */
    public WxacodeView wxacode(long roleId) {
        if (!wxacodeService.configured()) {
            return new WxacodeView(false, null,
                    "未配置小程序 AppID/Secret，暂用链接二维码；配置 WX_APP_ID/WX_SECRET 后自动切换为小程序码");
        }
        String token = repository.findTokenByRole(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "该角色还没有二维码，请先刷新列表"));
        // scene 上限 32 字符：令牌本身就是 32 位十六进制，不能再加 "t=" 前缀
        String scene = token.length() > 32 ? token.substring(0, 32) : token;
        return new WxacodeView(true, wxacodeService.unlimitedBase64("pages/bind/bind", scene), null);
    }

    /** 管理端列表：所有启用角色 + 二维码（没有的当场生成，省得管理员先点一次）。 */
    @Transactional
    public List<RoleQrView> listWithQr(Long operatorId) {
        return repository.listRolesWithQr().stream().map(row -> {
            String token = row.token();
            if (token == null || token.isBlank()) {
                token = newToken();
                repository.saveToken(row.roleId(), row.roleCode(), token, operatorId);
            }
            return new RoleQrView(row.roleId(), row.roleCode(), row.roleName(), token, row.bindCount());
        }).toList();
    }

    /** 重新生成：旧二维码立即失效。 */
    @Transactional
    public RoleQrView regenerate(long roleId, Long operatorId) {
        String roleCode = repository.findRoleCode(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "角色不存在或已停用"));
        String token = newToken();
        repository.saveToken(roleId, roleCode, token, operatorId);
        return new RoleQrView(roleId, roleCode,
                repository.listRolesWithQr().stream().filter(r -> r.roleId() == roleId)
                        .map(WxBindingRepository.RoleQrRow::roleName).findFirst().orElse(""),
                token, (int) repository.countBindingsByRole(roleId));
    }

    /** 小程序扫码后先看这是哪个角色的码。 */
    public PreviewView preview(String token) {
        var qr = repository.findByToken(EmployeeValidator.requireText(token, "二维码令牌"))
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "二维码已失效，请联系管理员重新生成"));
        return new PreviewView(qr.roleName(), qr.roleCode());
    }

    /**
     * 提交姓名+手机号完成绑定。
     *
     * @param wxCode 小程序 wx.login 的 code，用于换 openid（mock 模式下 code 即身份，需复用同一个 code）
     */
    @Transactional
    public BindResult bind(String token, String wxCode, String name, String phone) {
        var qr = repository.findByToken(EmployeeValidator.requireText(token, "二维码令牌"))
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "二维码已失效，请联系管理员重新生成"));
        String userName = EmployeeValidator.requireText(name, "姓名");
        String normalized = EmployeeValidator.validatePhone(EmployeeValidator.requireText(phone, "手机号"));
        if (normalized == null) {
            throw new BusinessException(ErrorCode.PHONE_INVALID, "手机号格式不正确");
        }
        String openid = appAuthService.resolveOpenid(wxCode);
        String phoneHmac = crypto.hmac(normalized);
        Long employeeId = employeeRepository.findByPhoneHmacAnyStatus(phoneHmac)
                .map(e -> e.getId()).orElse(null);

        long bindingId = repository.upsertBinding(openid, userName, crypto.encrypt(normalized),
                CryptoService.maskPhone(normalized), phoneHmac, employeeId);
        repository.addRole(bindingId, qr.roleId(), qr.roleCode(), qr.id());

        List<String> roles = repository.roleCodes(bindingId);
        log.info("微信角色绑定 openid={} 姓名={} 手机={} 角色={} 员工={}",
                openid, userName, CryptoService.maskPhone(normalized), roles, employeeId);
        return new BindResult(qr.roleName(), roles, appAuthService.loginByOpenid(openid));
    }

    public List<String> roleCodes(long bindingId) {
        return repository.roleCodes(bindingId);
    }

    /** 管理端：微信身份（绑定）列表。 */
    public List<WxBindingRepository.BindingRow> listBindings(String keyword) {
        return repository.listBindings(keyword);
    }

    /** 撤销某人的一个角色。 */
    public void revokeRole(long bindingId, String roleCode) {
        if (repository.deleteRole(bindingId, roleCode) == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该微信没有这个角色：" + roleCode);
        }
        log.info("撤销微信角色 binding={} 角色={}", bindingId, roleCode);
    }

    /** 彻底解绑（清空该微信的全部身份）。 */
    public void revokeBinding(long bindingId) {
        repository.deleteBinding(bindingId);
        log.info("解除微信绑定 binding={}", bindingId);
    }

    private String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
