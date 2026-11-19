-- 双审（C-65）：被访人确认（一级，员工）+ 门卫复核（二级，保安角色）
-- 目的：员工不能单方面把访客放进厂区，保安复核后才下发门禁授权。

ALTER TABLE approval_config
    ADD COLUMN l2_role VARCHAR(32) NOT NULL DEFAULT 'GUARD'
        COMMENT '二级复核角色（保安），该角色的后台账号都可处理' AFTER l2_candidate_ids;

ALTER TABLE approval_task
    ADD COLUMN assignee_type TINYINT NOT NULL DEFAULT 1
        COMMENT '1员工任务 2角色任务（assignee_id=0）' AFTER level,
    ADD COLUMN assignee_role VARCHAR(32) NULL
        COMMENT '角色任务的角色码，如 GUARD' AFTER assignee_type;

ALTER TABLE approval_action
    ADD COLUMN operator_type TINYINT NOT NULL DEFAULT 1
        COMMENT '1员工(App) 2后台账号' AFTER action,
    ADD COLUMN gate_snapshot VARCHAR(255) NULL
        COMMENT '本次审批批准的门点ID（逗号分隔），用于留痕对比' AFTER comment;

CREATE INDEX idx_assignee_role_status ON approval_task (assignee_role, status);

-- 默认口径改为双审：被访人确认 + 保安复核
UPDATE approval_config SET approval_level = 2 WHERE scope = 'GLOBAL';
