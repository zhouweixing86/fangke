package com.fangke.visitor.admin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.admin.repository.RoleAdminRepository;
import com.fangke.visitor.admin.service.RoleAdminService;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色与权限（C-73）：管理员可自由新增角色，并配置每个角色能看到哪些页面。
 *
 * <p>账号与角色是多对多：同一账号可分配多个角色，权限取并集；
 * 审批流节点的"处理角色"下拉也直接读这里，所以新建角色后立刻可用于审批。
 */
@RestController
@RequestMapping("/api/admin/roles")
public class RoleAdminController {

    public record CreateRequest(String roleCode, String roleName, String description,
                                List<String> perms, Integer sort) {
    }

    public record UpdateRequest(String roleName, String description, List<String> perms,
                                Integer status, Integer sort) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RoleAdminService service;

    public RoleAdminController(RoleAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.list().stream().map(this::toView).toList());
    }

    /** 可分配的权限清单（页面级），前端用它渲染复选框。 */
    @GetMapping("/permissions")
    public ApiResponse<List<Map<String, Object>>> permissions() {
        List<Map<String, Object>> list = service.catalog().stream().map(m -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("module", m.code());
            view.put("name", m.name());
            view.put("group", m.group());
            view.put("perm", m.code() + ":read");
            return view;
        }).toList();
        return ApiResponse.ok(list);
    }

    @PostMapping
    @AuditLog(module = "ROLE", action = "CREATE")
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest request) {
        long id = service.create(request.roleCode(), request.roleName(), request.description(),
                request.perms(), request.sort());
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/{id}")
    @AuditLog(module = "ROLE", action = "UPDATE")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        service.update(id, request.roleName(), request.description(), request.perms(),
                request.status(), request.sort());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @AuditLog(module = "ROLE", action = "DELETE")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    private Map<String, Object> toView(RoleAdminRepository.RoleDetail role) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", role.id());
        view.put("roleCode", role.roleCode());
        view.put("roleName", role.roleName());
        view.put("description", role.description() == null ? "" : role.description());
        view.put("perms", parse(role.permsJson()));
        view.put("builtin", role.builtin());
        view.put("status", role.status());
        view.put("sort", role.sort());
        view.put("userCount", role.userCount());
        return view;
    }

    private List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
