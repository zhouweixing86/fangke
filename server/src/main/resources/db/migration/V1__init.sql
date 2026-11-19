-- =====================================================================
-- P1 初始化：M1 基础框架 + M3 设备中心所需表
-- 完整表结构见 docs/10-数据库设计.md（33 张表），本文件只建 P1 用到的部分，
-- 后续模块用 V2/V3... 增量迁移补齐，禁止回头修改本文件。
-- =====================================================================

CREATE TABLE gate (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  gate_code  VARCHAR(32) NOT NULL,
  gate_name  VARCHAR(64) NOT NULL,
  gate_type  TINYINT NOT NULL DEFAULT 1 COMMENT '1大门 2侧门 3车库',
  status     TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_gate_code (gate_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门点';

CREATE TABLE device (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_sn      VARCHAR(64) NOT NULL COMMENT 'SN，如 T77R781116ZS',
  mac            VARCHAR(32) NOT NULL COMMENT 'dev_sno，如 0A:0C:E8:68:11:16',
  device_name    VARCHAR(64) NOT NULL,
  model          VARCHAR(64) NULL,
  firmware       VARCHAR(128) NULL,
  gate_id        BIGINT UNSIGNED NOT NULL,
  direction      TINYINT NOT NULL DEFAULT 1 COMMENT '0出口 1入口，对应 inout',
  channel        VARCHAR(8) NOT NULL DEFAULT 'LAN' COMMENT 'LAN / CLOUD',
  ip             VARCHAR(64) NULL,
  port           INT NOT NULL DEFAULT 8091,
  api_password   VARBINARY(255) NULL COMMENT '设备口令（应用层加密后写入）',
  capability     JSON NULL,
  online_state   TINYINT NOT NULL DEFAULT 0 COMMENT '0离线 1在线',
  last_heartbeat DATETIME(3) NULL,
  person_count   INT NULL,
  capacity       INT NULL,
  status         TINYINT NOT NULL DEFAULT 1,
  remark         VARCHAR(255) NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_sn (device_sn),
  UNIQUE KEY uk_mac (mac),
  KEY idx_gate (gate_id),
  KEY idx_online (online_state, last_heartbeat)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='终端设备';

CREATE TABLE device_param_snapshot (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id   BIGINT UNSIGNED NOT NULL,
  params      JSON NOT NULL,
  source      TINYINT NOT NULL COMMENT '1平台下发 2人工界面修改 3首次建档',
  operator_id BIGINT UNSIGNED NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_device_time (device_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备参数快照';

CREATE TABLE device_heartbeat (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id    BIGINT UNSIGNED NOT NULL,
  device_sn    VARCHAR(64) NOT NULL,
  device_ip    VARCHAR(64) NULL,
  person_count INT NULL,
  device_time  DATETIME NULL,
  clock_skew_s INT NULL,
  raw          JSON NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_device_time (device_id, created_at),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备心跳（滚动保留 7 天）';

CREATE TABLE device_cmd (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id   BIGINT UNSIGNED NOT NULL,
  cmd_type    VARCHAR(32) NOT NULL,
  priority    TINYINT NOT NULL DEFAULT 1,
  payload     JSON NULL,
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0待执行 1执行中 2成功 3失败 4放弃',
  retry_count INT NOT NULL DEFAULT 0,
  next_run_at DATETIME(3) NULL,
  error_code  VARCHAR(16) NULL,
  error_msg   VARCHAR(512) NULL,
  batch_id    BIGINT UNSIGNED NULL,
  started_at  DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_dequeue (device_id, status, priority, next_run_at),
  KEY idx_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台→设备指令队列';

-- P1 只建在线比对所需的字段子集；M4 用 V2 迁移补齐其余列
CREATE TABLE appointment (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_no  VARCHAR(20) NOT NULL,
  source          TINYINT NOT NULL DEFAULT 1 COMMENT '1访客自约 2员工代邀 3现场自助登记',
  visitor_id      BIGINT UNSIGNED NULL,
  start_time      DATETIME NOT NULL,
  end_time        DATETIME NOT NULL,
  mate_mode       TINYINT NOT NULL DEFAULT 0,
  approval_level  TINYINT NOT NULL DEFAULT 1,
  status          TINYINT NOT NULL DEFAULT 10 COMMENT '10草稿 20待一级 25待二级 30已通过 35可通行 40已签到 50已签离 60驳回 70过期 80撤回 90撤销',
  granted_at      DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_appointment_no (appointment_no),
  KEY idx_status_start (status, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约单（P1 子集）';

CREATE TABLE access_grant (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  subject_type   TINYINT NOT NULL COMMENT '1员工 2访客',
  subject_id     BIGINT UNSIGNED NOT NULL,
  person_no      VARCHAR(20) NOT NULL,
  appointment_id BIGINT UNSIGNED NULL,
  gate_id        BIGINT UNSIGNED NOT NULL,
  id_card_hmac   CHAR(64) NULL,
  valid_from     DATETIME NOT NULL COMMENT '预约当天 00:00:00（BR-V-007 / C-60）',
  valid_to       DATETIME NOT NULL COMMENT '预约当天 23:59:59',
  status         TINYINT NOT NULL DEFAULT 1 COMMENT '1生效 2待下发 3下发中 4已撤销 5已过期',
  revoked_at     DATETIME(3) NULL,
  revoked_by     BIGINT UNSIGNED NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_subject (subject_type, subject_id),
  KEY idx_person_no (person_no),
  KEY idx_status_valid (status, valid_to),
  KEY idx_gate (gate_id),
  KEY idx_appointment (appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通行授权';

CREATE TABLE access_event (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_uuid    VARCHAR(64) NOT NULL COMMENT '设备上报 id，做幂等',
  device_id     BIGINT UNSIGNED NULL,
  device_sn     VARCHAR(64) NOT NULL,
  gate_id       BIGINT UNSIGNED NULL,
  direction     TINYINT NOT NULL COMMENT '0出 1进（取设备 inout）',
  event_time    DATETIME NOT NULL,
  receive_time  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  identify_type TINYINT NOT NULL COMMENT '0人脸 2人证 3IC 5二维码',
  person_no     VARCHAR(20) NULL,
  person_name   VARCHAR(64) NULL,
  id_card_mask  VARCHAR(32) NULL,
  result_status TINYINT NOT NULL COMMENT '1成功 0失败',
  fail_reason   VARCHAR(64) NULL,
  score         DECIMAL(5,2) NULL,
  temperature   DECIMAL(4,1) NULL,
  mate_flag     TINYINT(1) NOT NULL DEFAULT 0,
  abnormal_flag TINYINT NOT NULL DEFAULT 0,
  source        TINYINT NOT NULL DEFAULT 1 COMMENT '1设备回调 2在线比对 3手工补录',
  raw           JSON NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_uuid (event_uuid),
  KEY idx_time (event_time),
  KEY idx_device_time (device_id, event_time),
  KEY idx_person_time (person_no, event_time),
  KEY idx_result (result_status, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通行事件';

CREATE TABLE access_event_media (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id   BIGINT UNSIGNED NOT NULL,
  media_type TINYINT NOT NULL COMMENT '1抓拍 2证件照 3模板照',
  oss_key    VARCHAR(255) NOT NULL,
  width      INT NULL,
  height     INT NULL,
  size_bytes INT NULL,
  watermark  TINYINT(1) NOT NULL DEFAULT 1,
  expire_at  DATETIME NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_event (event_id),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件照片（OSS key）';

CREATE TABLE sys_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  real_name     VARCHAR(64) NOT NULL,
  employee_id   BIGINT UNSIGNED NULL,
  user_type     TINYINT NOT NULL DEFAULT 1 COMMENT '1管理员 2访客管理员 3保安',
  status        TINYINT NOT NULL DEFAULT 1,
  last_login_at DATETIME(3) NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at    DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台账号';

CREATE TABLE sys_role (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  role_code  VARCHAR(32) NOT NULL,
  role_name  VARCHAR(64) NOT NULL,
  perms      JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE sys_user_role (
  id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色';

CREATE TABLE sys_config (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(64) NOT NULL,
  config_value VARCHAR(1024) NULL,
  is_encrypted TINYINT(1) NOT NULL DEFAULT 0,
  remark       VARCHAR(255) NULL,
  updated_by   BIGINT UNSIGNED NULL,
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置';

CREATE TABLE audit_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NULL,
  user_name   VARCHAR(64) NULL,
  module      VARCHAR(64) NOT NULL,
  action      VARCHAR(64) NOT NULL,
  target      VARCHAR(128) NULL,
  request_uri VARCHAR(255) NULL,
  request_ip  VARCHAR(64) NULL,
  detail      JSON NULL,
  result      TINYINT NOT NULL DEFAULT 1,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  KEY idx_module_time (module, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志（不可删除，BR-D-010）';
