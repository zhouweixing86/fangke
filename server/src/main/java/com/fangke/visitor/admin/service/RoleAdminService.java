package com.fangke.visitor.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.admin.repository.RoleAdminRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色与权限（C-73）。
 *
 * <p>权限码口径（都写在 {@code sys_role.perms} 数组里）：
 * <ul>
 *   <li>{@code *}：全部权限（ADMIN 默认）</li>
 *   <li>{@code module:read}：能看到这个页面并调用它的查询接口</li>
 *   <li>{@code module:*}：该模块全权（目前等价于 read，预留写权限细分）</li>
 * </ul>
 * 一个账号有多个角色时取**并集**；同一角色可分配给多个账号。
 */
@Service
public class RoleAdminService {

    private static final Logger log = LoggerFactory.getLogger(RoleAdminService.class);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 页面/模块清单：前端菜单与后端接口映射都用这套 code。 */
    public record Module(String code, String name, String group) {
    }

    public static final List<Module> MODULES = List.of(
            new Module("dashboard", "保安工作台", "工作台"),
            new Module("guard", "门卫审核", "工作台"),
            new Module("appointment", "预约查询", "业务办理"),
            new Module("event", "通行记录", "业务办理"),
            new Module("blacklist", "黑名单", "业务办理"),
            new Module("device", "设备管理", "业务办理"),
            new Module("gate", "门点管理", "基础档案"),
            new Module("dept", "部门管理", "基础档案"),
            new Module("employee", "员工管理", "基础档案"),
            new Module("report", "报表统计", "数据"),
            new Module("batch", "下发批次", "数据"),
            new Module("user", "账号与角色", "系统"),
            new Module("audit", "审计日志", "系统"),
            new Module("ops", "运维中心", "系统"),
            new Module("approval", "审批配置", "系统"));

    private static final Set<String> MODULE_CODES = MODULES.stream()
            .map(Module::code).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final RoleAdminRepository repository;

    public RoleAdminService(RoleAdminRepository repository) {
        this.repository = repository;
    }

    /* ---------------- 查询 ---------------- */

    public List<RoleAdminRepository.RoleDetail> list() {
        return repository.list();
    }

    public List<Module> catalog() {
        return MODULES;
    }

    public List<String> permissionsOfRoles(List<String> roles) {
        return repository.permissionsOfRoles(roles);
    }

    /** 权限判定：支持 {@code *} 与 {@code module:*} 通配。 */
    public boolean hasPermission(List<String> permissions, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        String module = required.contains(":") ? required.substring(0, required.indexOf(':')) : required;
        for (String perm : permissions) {
            if ("*".equals(perm) || required.equals(perm)) {
                return true;
            }
            if ((module + ":*").equals(perm)) {
                return true;
            }
        }
        return false;
    }

    /* ---------------- 维护 ---------------- */

    @Transactional
    public long create(String roleCode, String roleName, String description, List<String> perms, Integer sort) {
        String code = normalizeCode(roleCode);
        if (repository.existsCode(code)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "角色编码已存在：" + code);
        }
        String name = requireText(roleName, "角色名称");
        long id = repository.create(code, name, trim(description), toJson(validatePerms(perms)),
                sort == null ? 0 : sort);
        log.info("新增角色 id={} code={} perms={}", id, code, perms);
        return id;
    }

    @Transactional
    public void update(Long id, String roleName, String description, List<String> perms,
                       Integer status, Integer sort) {
        RoleAdminRepository.RoleDetail role = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "角色不存在：" + id));
        List<String> normalized = validatePerms(perms);
        if ("ADMIN".equals(role.roleCode())) {
            // 管理员权限固定全量，避免把自己锁死
            normalized = List.of("*");
        }
        repository.update(id, requireText(roleName, "角色名称"), trim(description),
                toJson(normalized), status, sort == null ? role.sort() : sort);
        log.info("更新角色 id={} perms={} status={}", id, normalized, status);
    }

    @Transactional
    public void delete(Long id) {
        RoleAdminRepository.RoleDetail role = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "角色不存在：" + id));
        if (role.builtin()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "内置角色不能删除：" + role.roleCode());
        }
        if (role.userCount() > 0) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "还有 " + role.userCount() + " 个账号在用这个角色，请先改掉账号的角色");
        }
        repository.delete(id);
        log.info("删除角色 id={} code={}", id, role.roleCode());
    }

    /* ---------------- 内部 ---------------- */

    public List<String> validatePerms(List<String> perms) {
        if (perms == null || perms.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String raw : perms) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String perm = raw.trim();
            if ("*".equals(perm)) {
                result.add("*");
                continue;
            }
            int idx = perm.indexOf(':');
            if (idx <= 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "权限码格式应为 module:read：" + perm);
            }
            String module = perm.substring(0, idx);
            String action = perm.substring(idx + 1);
            if (!MODULE_CODES.contains(module)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "未知模块：" + module);
            }
            if (!"read".equals(action) && !"*".equals(action)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "暂只支持 read / * 权限：" + perm);
            }
            result.add(module + ":" + action);
        }
        return new ArrayList<>(result);
    }

    private String normalizeCode(String roleCode) {
        String code = requireText(roleCode, "角色编码").toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "角色编码只能是 2~32 位大写字母/数字/下划线，且以字母开头");
        }
        return code;
    }

    private String toJson(List<String> perms) {
        try {
            return MAPPER.writeValueAsString(perms == null ? List.of() : perms);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "权限序列化失败：" + e.getMessage());
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, field + "不能为空");
        }
        String text = value.trim();
        if (text.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, field + "最长 64 字");
        }
        return text;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : (text.length() > 255 ? text.substring(0, 255) : text);
    }

    /** 供前端展示：权限码 → 中文名。 */
    public Map<String, String> permissionLabels() {
        return MODULES.stream().collect(java.util.stream.Collectors.toMap(
                Module::code, Module::name, (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
