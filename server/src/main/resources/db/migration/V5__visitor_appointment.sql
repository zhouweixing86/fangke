-- =====================================================================
-- M4 访客与预约（结构同 docs/10 §5）
-- =====================================================================

CREATE TABLE visitor (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  person_no      VARCHAR(20) NOT NULL COMMENT 'V+yyyyMMdd+4位序号',
  name           VARCHAR(64) NOT NULL,
  phone_enc      VARBINARY(255) NULL,
  phone_hmac     CHAR(64) NULL,
  phone_mask     VARCHAR(20) NULL,
  id_card_enc    VARBINARY(255) NOT NULL,
  id_card_hmac   CHAR(64) NOT NULL,
  id_card_mask   VARCHAR(32) NOT NULL,
  company        VARCHAR(128) NULL,
  id_issue       VARCHAR(128) NULL,
  id_address     VARCHAR(255) NULL,
  first_visit_at DATETIME NULL,
  last_visit_at  DATETIME NULL,
  visit_count    INT NOT NULL DEFAULT 0,
  is_blacklist   TINYINT(1) NOT NULL DEFAULT 0,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_person_no (person_no),
  UNIQUE KEY uk_id_card_hmac (id_card_hmac),
  KEY idx_phone_hmac (phone_hmac),
  KEY idx_blacklist (is_blacklist)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访客档案（按身份证号复用）';

CREATE TABLE visitor_consent (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  visitor_id   BIGINT UNSIGNED NOT NULL,
  consent_type TINYINT NOT NULL COMMENT '1个人信息处理告知 2代邀授权',
  version      VARCHAR(32) NOT NULL,
  agreed_at    DATETIME(3) NOT NULL,
  ip           VARCHAR(64) NULL,
  ua           VARCHAR(255) NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_visitor (visitor_id, consent_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隐私同意记录（不可删）';

ALTER TABLE appointment
  ADD COLUMN visit_employee_id BIGINT UNSIGNED NULL COMMENT '被访员工' AFTER visitor_id,
  ADD COLUMN visit_phone_enc VARBINARY(255) NULL COMMENT '访客填的被访人手机号' AFTER visit_employee_id,
  ADD COLUMN visit_phone_mask VARCHAR(20) NULL AFTER visit_phone_enc,
  ADD COLUMN visitor_type VARCHAR(16) NULL COMMENT '商务/施工/送货（C-51）' AFTER visit_phone_mask,
  ADD COLUMN purpose VARCHAR(100) NULL COMMENT '来访事由，选填（C-53）' AFTER visitor_type,
  ADD COLUMN plate_no VARCHAR(16) NULL COMMENT '车牌，选填（C-50）' AFTER purpose,
  ADD COLUMN department VARCHAR(64) NULL AFTER plate_no,
  ADD COLUMN mate_count INT NOT NULL DEFAULT 0 AFTER mate_mode,
  ADD COLUMN reject_reason VARCHAR(255) NULL AFTER status,
  ADD COLUMN cancel_reason VARCHAR(255) NULL AFTER reject_reason,
  ADD COLUMN current_task_id BIGINT UNSIGNED NULL AFTER cancel_reason;

CREATE TABLE appointment_mate (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id BIGINT UNSIGNED NOT NULL,
  name           VARCHAR(64) NOT NULL,
  id_card_enc    VARBINARY(255) NULL,
  id_card_hmac   CHAR(64) NULL,
  id_card_mask   VARCHAR(32) NULL,
  phone_enc      VARBINARY(255) NULL,
  phone_mask     VARCHAR(20) NULL,
  is_main        TINYINT(1) NOT NULL DEFAULT 0,
  person_no      VARCHAR(20) NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_appointment (appointment_id),
  KEY idx_id_card_hmac (id_card_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约同行人';

CREATE TABLE blacklist (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  target_type TINYINT NOT NULL COMMENT '1身份证号 2手机号',
  target_enc  VARBINARY(255) NOT NULL,
  target_hmac CHAR(64) NOT NULL,
  target_mask VARCHAR(64) NOT NULL,
  reason      VARCHAR(255) NOT NULL,
  valid_from  DATETIME NULL,
  valid_to    DATETIME NULL,
  status      TINYINT NOT NULL DEFAULT 1 COMMENT '1生效 0失效',
  created_by  BIGINT UNSIGNED NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_target (target_type, target_hmac),
  KEY idx_status (status, valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='黑名单';
