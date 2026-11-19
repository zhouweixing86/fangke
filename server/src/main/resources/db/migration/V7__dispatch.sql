-- =====================================================================
-- M6 授权下发（结构同 docs/10 §7）
-- =====================================================================

CREATE TABLE sync_batch (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_no      VARCHAR(32) NOT NULL,
  trigger_type  TINYINT NOT NULL COMMENT '1审批通过 2定时对账 3手动重下 4首次全量 5清理',
  gate_id       BIGINT UNSIGNED NULL,
  total_count   INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  fail_count    INT NOT NULL DEFAULT 0,
  status        TINYINT NOT NULL DEFAULT 0 COMMENT '0进行中 1成功 2部分失败 3全失败',
  started_at    DATETIME(3) NULL,
  finished_at   DATETIME(3) NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_no (batch_no),
  KEY idx_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下发批次';

CREATE TABLE sync_batch_item (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id   BIGINT UNSIGNED NOT NULL,
  device_id  BIGINT UNSIGNED NOT NULL,
  person_no  VARCHAR(20) NOT NULL,
  action     TINYINT NOT NULL COMMENT '1新增/覆盖 2删除',
  status     TINYINT NOT NULL DEFAULT 0 COMMENT '0待执行 1成功 2失败',
  error_code VARCHAR(16) NULL,
  error_msg  VARCHAR(512) NULL,
  acked_at   DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_batch (batch_id),
  KEY idx_device_person (device_id, person_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下发批次明细';
