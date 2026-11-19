package com.fangke.visitor.admin.service;

import com.fangke.visitor.admin.repository.AdminUserRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 后台账号与角色管理（docs/11 §5.9）。
 *
 * <p>几条硬约束：
 * <ul>
 *   <li>账号名唯一、3~32 位字母数字下划线点短横线；</li>
 *   <li>口令至少 8 位且同时含字母和数字，BCrypt 存储；</li>
 *   <li>不能停用自己，也不能把最后一个在职管理员停用或降权（否则没人能进后台）；</li>
 *   <li>角色以编码为准，必须来自 sys_role 字典。</li>
 * </ul>
 * 角色写在 JWT 里，改完角色后需重新登录才生效。
 */
@Service
public class UserAdminService {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_GUARD = "GUARD";

    private final AdminUserRepository repository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserAdminService(AdminUserRepository repository) {
        this.repository = repository;
    }

    public record Page(List<AdminUserRepository.Row> list, long total) {
    }

    public Page search(String keyword, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return new Page(repository.search(keyword, status, (safePage - 1) * safeSize, safeSize),
                repository.count(keyword, status));
    }

    public List<AdminUserRepository.RoleRow> roles() {
        return repository.roles();
    }

    @Transactional
    public Long create(String username, String realName, Integer userType, String password, List<String> roles) {
        String name = requireText(username, "账号").trim();
        if (!name.matches("[A-Za-z0-9_.-]{3,32}")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "账号只能是 3~32 位字母、数字、下划线、点或短横线");
        }
        if (repository.existsUsername(name)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "账号已存在：" + name);
        }
        int type = validateUserType(userType);
        String display = requireText(realName, "姓名");
        if (display.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "姓名不能超过 64 字");
        }
        String raw = validatePassword(password);
        List<String> codes = validateRoles(roles);

        long id = repository.insert(name, encoder.encode(raw), display, type);
        repository.replaceRoles(id, codes);
        return id;
    }

    @Transactional
    public void update(Long id, String realName, Integer userType, List<String> roles, Long operatorId) {
        AdminUserRepository.Row current = load(id);
        String display = requireText(realName, "姓名");
        if (display.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "姓名不能超过 64 字");
        }
        int type = validateUserType(userType);
        List<String> codes = validateRoles(roles);

        boolean wasAdmin = current.roles().contains(ROLE_ADMIN);
        boolean willBeAdmin = codes.contains(ROLE_ADMIN);
        if (wasAdmin && !willBeAdmin && isLastActiveAdmin(id)) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该账号是最后一个管理员，不能移除管理员角色");
        }

        repository.updateProfile(id, display, type);
        repository.replaceRoles(id, codes);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        load(id);
        repository.updatePassword(id, encoder.encode(validatePassword(newPassword)));
    }

    @Transactional
    public void changeStatus(Long id, Integer status, Long operatorId) {
        AdminUserRepository.Row current = load(id);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "状态只能是 1（启用）或 0（停用）");
        }
        if (status == 0) {
            if (operatorId != null && operatorId.equals(id)) {
                throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "不能停用当前登录的账号");
            }
            if (current.roles().contains(ROLE_ADMIN) && isLastActiveAdmin(id)) {
                throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该账号是最后一个管理员，不能停用");
            }
        }
        repository.updateStatus(id, status);
    }

    /** 自己改密码：必须先验旧口令。 */
    @Transactional
    public void changeOwnPassword(Long userId, String oldPassword, String newPassword) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String hash = repository.findPasswordHash(userId);
        if (hash == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "账号不存在：" + userId);
        }
        if (oldPassword == null || !encoder.matches(oldPassword, hash)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "原密码不正确");
        }
        if (encoder.matches(newPassword == null ? "" : newPassword, hash)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }
        repository.updatePassword(userId, encoder.encode(validatePassword(newPassword)));
    }

    private AdminUserRepository.Row load(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "账号 ID 不能为空");
        }
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "账号不存在：" + id));
    }

    private boolean isLastActiveAdmin(Long id) {
        AdminUserRepository.Row current = repository.findById(id).orElseThrow();
        if (current.status() != 1) {
            return false;
        }
        return repository.countActiveByRole(ROLE_ADMIN) <= 1;
    }

    private int validateUserType(Integer userType) {
        int type = userType == null ? 1 : userType;
        if (type < 1 || type > 3) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "账号类型只能是 1 管理员 / 2 访客管理员 / 3 保安");
        }
        return type;
    }

    private String validatePassword(String password) {
        String raw = requireText(password, "密码");
        if (raw.length() < 8 || raw.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "密码长度需 8~64 位");
        }
        boolean hasLetter = raw.chars().anyMatch(Character::isLetter);
        boolean hasDigit = raw.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "密码必须同时包含字母和数字");
        }
        return raw;
    }

    private List<String> validateRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "至少选择一个角色");
        }
        Set<String> known = repository.roles().stream()
                .map(AdminUserRepository.RoleRow::roleCode)
                .collect(Collectors.toSet());
        List<String> codes = new ArrayList<>();
        for (String role : roles) {
            String code = role == null ? "" : role.trim().toUpperCase();
            if (code.isEmpty()) {
                continue;
            }
            if (!known.contains(code)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "未知角色：" + role);
            }
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "至少选择一个角色");
        }
        return codes;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, field + "不能为空");
        }
        return value.trim();
    }
}
