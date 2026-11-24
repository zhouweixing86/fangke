-- =====================================================================
-- RBAC（C-73）：角色可自由增删 + 按角色控制页面/接口权限
--
-- 现状：sys_role 已有 perms JSON（历史占位），但只有 ADMIN/GUARD/VISITOR_ADMIN
-- 三个固定角色，且 /api/admin/** 只放行 ADMIN，无法按角色分页面。
--
-- 本次：
--   1. sys_role 增加描述/内置标记/状态/排序
--   2. 权限码统一为 module:read（页面级）与 module:*（模块全权），"*" 为全部
--   3. 给三个内置角色补齐权限（管理员全量；保安：工作台/审核/预约/通行/设备；访客管理员：档案与报表）
-- =====================================================================

ALTER TABLE sys_role
    ADD COLUMN description VARCHAR(255) NULL AFTER role_name,
    ADD COLUMN builtin TINYINT NOT NULL DEFAULT 0 COMMENT '1=内置角色（不可删除）' AFTER perms,
    ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用' AFTER builtin,
    ADD COLUMN sort INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;

UPDATE sys_role SET builtin = 1 WHERE role_code IN ('ADMIN', 'GUARD', 'VISITOR_ADMIN');

UPDATE sys_role
SET description = '系统管理员：全部页面与操作'
WHERE role_code = 'ADMIN';

UPDATE sys_role
SET description = '保安：门卫工作台、预约审核、通行记录'
WHERE role_code = 'GUARD';

UPDATE sys_role
SET description = '访客管理员：员工档案、黑名单、报表'
WHERE role_code = 'VISITOR_ADMIN';

UPDATE sys_role SET perms = '["*"]' WHERE role_code = 'ADMIN';

UPDATE sys_role
SET perms = '["dashboard:read","guard:*","appointment:read","event:read","device:read"]'
WHERE role_code = 'GUARD';

UPDATE sys_role
SET perms = '["dashboard:read","employee:read","blacklist:read","report:read","appointment:read","event:read","device:read"]'
WHERE role_code = 'VISITOR_ADMIN';
