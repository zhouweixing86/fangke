package com.fangke.visitor.employee.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.employee.repository.DeptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门维护规则：管理端自助建部门，员工档案/导入都依赖它。
 */
class DeptServiceTest {

    private InMemoryDeptRepository repo;
    private DeptService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryDeptRepository()
                .put(1L, "ADM", "行政部", 0L, 1, 2)
                .put(2L, "IT", "信息部", 0L, 2, 0);
        service = new DeptService(repo);
    }

    @Test
    @DisplayName("新增部门：编码统一转大写，列表带员工数")
    void createNormalizesCode() {
        long id = service.create("sales", "销售部", null, 3);

        DeptRepository.Dept saved = service.require(id);
        assertEquals("SALES", saved.code());
        assertEquals("销售部", saved.name());
        assertEquals(0L, saved.employeeCount());
        assertEquals(3, service.list().size());
    }

    @Test
    @DisplayName("部门编码唯一（忽略大小写与前后空格）")
    void codeMustBeUnique() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(" adm ", "重复行政部", null, 0));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("编码格式与名称必填")
    void validation() {
        assertEquals(ErrorCode.PARAM_MISSING.code(),
                assertThrows(BusinessException.class, () -> service.create(null, "某部", null, 0)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(),
                assertThrows(BusinessException.class, () -> service.create("中文编码", "某部", null, 0)).getCode());
        assertEquals(ErrorCode.PARAM_MISSING.code(),
                assertThrows(BusinessException.class, () -> service.create("OK1", "  ", null, 0)).getCode());
    }

    @Test
    @DisplayName("上级部门必须存在，且不能是自己")
    void parentRules() {
        assertEquals(ErrorCode.PARAM_INVALID.code(),
                assertThrows(BusinessException.class, () -> service.create("X1", "某部", 99L, 0)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(),
                assertThrows(BusinessException.class, () -> service.update(1L, "行政部", 1L, 1)).getCode());
    }

    @Test
    @DisplayName("有员工的部门不能删除（要先把员工转移）")
    void cannotDeleteDeptWithEmployees() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.delete(1L));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), e.getCode());
        assertTrue(e.getMessage().contains("2"), e.getMessage());
        assertTrue(service.list().stream().anyMatch(d -> "ADM".equals(d.code())), "部门应仍然存在");
    }

    @Test
    @DisplayName("有子部门的部门不能删除")
    void cannotDeleteDeptWithChildren() {
        long parent = service.create("PROD", "生产部", null, 5);
        service.create("PROD_A", "一车间", parent, 1);

        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(),
                assertThrows(BusinessException.class, () -> service.delete(parent)).getCode());
    }

    @Test
    @DisplayName("空部门可删除，删除后不再出现在列表里")
    void deleteEmptyDept() {
        service.delete(2L);
        assertEquals(1, service.list().size());
        assertTrue(service.list().stream().noneMatch(d -> "IT".equals(d.code())));
    }
}
