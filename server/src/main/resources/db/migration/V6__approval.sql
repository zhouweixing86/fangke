-- =====================================================================
-- M5 审批流（结构同 docs/10 §6）
-- =====================================================================

CREATE TABLE approval_config (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scope                 VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
  approval_level        TINYINT NOT NULL DEFAULT 1 COMMENT '1 或 2',
  default_l1_user       BIGINT UNSIGNED NULL COMMENT '被访人手机号匹配不到时的默认一级审批人',
  l2_candidate_ids      JSON NULL COMMENT '二级候选池（员工ID数组）',
  reminder_interval_min INT NOT NULL DEFAULT 30,
  reminder_max_per_task INT NOT NULL DEFAULT 2,
  status                TINYINT NOT NULL DEFAULT 1,
  created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_scope (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批配置';

CREATE TABLE approval_task (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id   BIGINT UNSIGNED NOT NULL,
  level            TINYINT NOT NULL COMMENT '1 一级 2 二级',
  assignee_id      BIGINT UNSIGNED NOT NULL,
  status           TINYINT NOT NULL DEFAULT 1 COMMENT '1待处理 2已通过 3已驳回 4已跳过 5已转办 6已失效',
  deadline_at      DATETIME(3) NULL,
  reminder_count   INT NOT NULL DEFAULT 0,
  last_reminder_at DATETIME(3) NULL,
  finished_at      DATETIME(3) NULL,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_assignee_status (assignee_id, status),
  KEY idx_appointment (appointment_id, level),
  KEY idx_reminder (status, last_reminder_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批任务';

CREATE TABLE approval_action (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id        BIGINT UNSIGNED NOT NULL,
  appointment_id BIGINT UNSIGNED NOT NULL,
  operator_id    BIGINT UNSIGNED NOT NULL,
  action         TINYINT NOT NULL COMMENT '1通过 2驳回 3转办 4加签 5催办 6系统跳过',
  target_user    BIGINT UNSIGNED NULL,
  comment        VARCHAR(500) NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_task (task_id),
  KEY idx_appointment (appointment_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批动作流水（不可改）';

INSERT INTO approval_config (scope, approval_level, reminder_interval_min, reminder_max_per_task)
VALUES ('GLOBAL', 1, 30, 2);
