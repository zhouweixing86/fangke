-- 审批改为单级：由登录管理端的人审核（2026-11-21 客户确认）
--
-- 变更内容：
--   1. 员工不再登录审批，预约统一由管理端（保安/管理员角色）审核
--   2. 取消"被访人一级 + 保安二级"的双审，只保留一级
--   3. 被访人（visit_employee_id）仅作为"拜访对象"记录，不再参与审批
--
-- 保留 approval_config 表与 approval_level 字段（历史数据可追溯），但只允许 1。
UPDATE approval_config SET approval_level = 1 WHERE approval_level <> 1;

ALTER TABLE approval_config
    MODIFY COLUMN approval_level TINYINT NOT NULL DEFAULT 1
        COMMENT '审批级数：固定 1（单级，管理端审核）。历史上曾支持 2（被访人 + 保安双审）';

-- 清理历史遗留的"指派给员工"的待办：改成角色任务，避免升级后卡住
UPDATE approval_task
SET assignee_type = 2,
    assignee_role = COALESCE((SELECT l2_role FROM approval_config WHERE status = 1 LIMIT 1), 'GUARD'),
    assignee_id = 0
WHERE status = 1 AND assignee_type = 1;
