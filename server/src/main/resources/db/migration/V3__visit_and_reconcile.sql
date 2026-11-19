-- =====================================================================
-- P1 收尾：签到/签离与名单对账所需表（结构同 docs/10 §8、§7）
-- =====================================================================

CREATE TABLE visit_record (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id BIGINT UNSIGNED NOT NULL,
  visitor_id     BIGINT UNSIGNED NULL,
  gate_id        BIGINT UNSIGNED NOT NULL,
  checkin_at     DATETIME NULL COMMENT '首次通行成功（入口设备）',
  checkin_event  BIGINT UNSIGNED NULL,
  checkout_at    DATETIME NULL COMMENT '出口设备识别或人工签离',
  checkout_event BIGINT UNSIGNED NULL,
  checkout_type  TINYINT NULL COMMENT '1设备识别 2保安手工 3超时自动',
  visit_date     DATE NOT NULL,
  status         TINYINT NOT NULL DEFAULT 1 COMMENT '1在访 2已离场 3异常离场 4超时自动签离',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_appointment (appointment_id),
  KEY idx_visit_date (visit_date),
  KEY idx_status (status),
  KEY idx_visitor (visitor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='到访记录（签到/签离）';

CREATE TABLE person_device_state (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id       BIGINT UNSIGNED NOT NULL,
  person_no       VARCHAR(20) NOT NULL,
  expect_state    TINYINT NOT NULL COMMENT '1应在名单 2不应在',
  actual_state    TINYINT NULL COMMENT '1在名单 2不在 3未知',
  last_synced_at  DATETIME(3) NULL,
  last_checked_at DATETIME(3) NULL,
  diff_flag       TINYINT NOT NULL DEFAULT 0 COMMENT '1差异待修复 0一致',
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_person (device_id, person_no),
  KEY idx_diff (device_id, diff_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台期望 vs 设备实际（JOB-04 对账）';
