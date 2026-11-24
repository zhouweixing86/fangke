-- =====================================================================
-- 审批重构（C-72）：多级审批模板 + 节点 + 抄送
--
-- 背景：原实现是"单级、角色、写死在 approval_config"。
-- 现在改成"模板 → 节点（串行） → 抄送"，管理端可自行增删节点。
--
-- 兼容策略：
--   1. approval_config 保留（催办间隔/上限仍用它；l2_role 作为无模板时的兜底角色）
--   2. approval_task.level 继续存节点序号（seq），历史 level 语义不变
--   3. 已有待办补 node_name，避免列表显示空白
-- =====================================================================

CREATE TABLE approval_template (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  template_name VARCHAR(64) NOT NULL,
  scope         VARCHAR(32) NOT NULL DEFAULT 'GLOBAL' COMMENT '适用范围：当前只有 GLOBAL',
  enabled       TINYINT NOT NULL DEFAULT 1,
  remark        VARCHAR(255) NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_scope_enabled (scope, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批模板（多级）';

CREATE TABLE approval_node (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  template_id      BIGINT UNSIGNED NOT NULL,
  seq              INT NOT NULL COMMENT '节点顺序，从 1 开始',
  node_name        VARCHAR(64) NOT NULL COMMENT '如"保安审核""行政复核"',
  assignee_type    TINYINT NOT NULL DEFAULT 2 COMMENT '2角色（当前仅支持角色）',
  assignee_role    VARCHAR(32) NOT NULL COMMENT '处理角色，如 GUARD/ADMIN',
  approve_mode     VARCHAR(8) NOT NULL DEFAULT 'SINGLE'
                   COMMENT 'SINGLE单签 / ALL会签（该角色全部账号）/ ANY或签（任一人）',
  allow_edit_gates TINYINT NOT NULL DEFAULT 1 COMMENT '该节点能否调整开放门点',
  allow_qr         TINYINT NOT NULL DEFAULT 1 COMMENT '该节点能否审批二维码开门',
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_template_seq (template_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批节点（串行）';

CREATE TABLE approval_cc (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  template_id  BIGINT UNSIGNED NOT NULL,
  node_id      BIGINT UNSIGNED NULL COMMENT 'NULL=预约提交时抄送；否则该节点办结后抄送',
  target_type  TINYINT NOT NULL DEFAULT 2 COMMENT '2角色',
  target_value VARCHAR(32) NOT NULL COMMENT '角色码，如 ADMIN',
  channel      TINYINT NOT NULL DEFAULT 1 COMMENT '1站内通知',
  enabled      TINYINT NOT NULL DEFAULT 1,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批抄送配置';

ALTER TABLE approval_task
    ADD COLUMN template_id BIGINT UNSIGNED NULL AFTER appointment_id,
    ADD COLUMN node_id     BIGINT UNSIGNED NULL AFTER template_id,
    ADD COLUMN node_name   VARCHAR(64) NULL AFTER node_id;

ALTER TABLE approval_action
    MODIFY COLUMN action TINYINT NOT NULL
        COMMENT '1通过 2驳回 3转办 4加签 5催办 6系统跳过 7抄送';

-- 旧配置 → 默认模板（全局唯一）+ 一个"管理端审核"节点
INSERT INTO approval_template (template_name, scope, enabled, remark)
SELECT '默认审批流程', 'GLOBAL', 1, '由单级审批升级（V24），可在管理端增删节点'
WHERE NOT EXISTS (SELECT 1 FROM approval_template WHERE scope = 'GLOBAL');

INSERT INTO approval_node (template_id, seq, node_name, assignee_type, assignee_role, approve_mode)
SELECT t.id, 1, '管理端审核', 2, COALESCE(c.l2_role, 'GUARD'), 'SINGLE'
FROM approval_template t
LEFT JOIN approval_config c ON c.scope = 'GLOBAL'
WHERE t.scope = 'GLOBAL'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.template_id = t.id);

-- 历史待办补节点名（列表/详情显示用）
UPDATE approval_task
SET node_name = CASE WHEN level <= 1 THEN '管理端审核' ELSE CONCAT('第 ', level, ' 级审核') END
WHERE node_name IS NULL;
