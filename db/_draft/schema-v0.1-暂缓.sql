-- =====================================================================
-- 访客管理系统 数据库结构 v1.0
-- MySQL 8.0+ / InnoDB / utf8mb4
-- 前提：单租户、单园区、单门点（访客只走公司大门）、员工全门点通行
-- 说明：保留 tenant_id 便于后续扩展；敏感字段以「密文 + HMAC 摘要 + 脱敏明文」三列存储
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 0. 系统参数
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sys_config;
CREATE TABLE sys_config (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT UNSIGNED NOT NULL DEFAULT 1,
  cfg_key     VARCHAR(64)     NOT NULL COMMENT '配置键',
  cfg_value   VARCHAR(1024)   NOT NULL COMMENT '配置值',
  remark      VARCHAR(255)             DEFAULT NULL,
  updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cfg (tenant_id, cfg_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数（码有效期/清理策略/保留期限等）';

-- ---------------------------------------------------------------------
-- 1. 组织与设备
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS park;
CREATE TABLE park (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT UNSIGNED NOT NULL DEFAULT 1,
  park_code   VARCHAR(32)     NOT NULL COMMENT '园区编码',
  park_name   VARCHAR(64)     NOT NULL COMMENT '园区名称',
  address     VARCHAR(255)             DEFAULT NULL,
  status      TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_park (tenant_id, park_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='园区';

DROP TABLE IF EXISTS area;
CREATE TABLE area (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id        BIGINT UNSIGNED NOT NULL DEFAULT 1,
  park_id          BIGINT UNSIGNED NOT NULL,
  area_code        VARCHAR(32)     NOT NULL COMMENT '门点编码',
  area_name        VARCHAR(64)     NOT NULL COMMENT '门点名称',
  is_visitor_gate  TINYINT         NOT NULL DEFAULT 0 COMMENT '是否访客可通行门点（本期仅公司大门=1）',
  status           TINYINT         NOT NULL DEFAULT 1,
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_area (tenant_id, area_code),
  KEY idx_park (park_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门点/区域';

DROP TABLE IF EXISTS device;
CREATE TABLE device (
  id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id              BIGINT UNSIGNED NOT NULL DEFAULT 1,
  park_id                BIGINT UNSIGNED NOT NULL,
  area_id                BIGINT UNSIGNED NOT NULL,
  dev_sno                VARCHAR(32)     NOT NULL COMMENT '设备号=MAC，同时是 MQTT topic',
  dev_sn                 VARCHAR(64)              DEFAULT NULL COMMENT '设备SN',
  register_no            VARCHAR(64)              DEFAULT NULL COMMENT '设备注册号',
  device_name            VARCHAR(64)     NOT NULL COMMENT '设备名称',
  inout                  TINYINT         NOT NULL DEFAULT 1 COMMENT '1入口 2出口',
  ip                     VARCHAR(45)              DEFAULT NULL COMMENT '最近上报 IP，用于白名单校验',
  firmware_version       VARCHAR(32)              DEFAULT NULL,
  support_online_compare TINYINT         NOT NULL DEFAULT 0 COMMENT 'W0-V2：是否支持在线比对接口',
  support_effective_date TINYINT         NOT NULL DEFAULT 0 COMMENT 'W0-V3：是否真正执行有效期',
  person_capacity        INT             NOT NULL DEFAULT 0 COMMENT 'W0-V5：本地人员容量上限',
  person_count           INT             NOT NULL DEFAULT 0 COMMENT '设备最近上报的人员数',
  online_state           TINYINT         NOT NULL DEFAULT 0 COMMENT '0离线 1在线',
  last_heartbeat_at      DATETIME(3)              DEFAULT NULL,
  status                 TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_dev_sno (dev_sno),
  KEY idx_area (area_id),
  KEY idx_online (online_state, last_heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备（九鼎 RV1109）';

DROP TABLE IF EXISTS device_config_snap;
CREATE TABLE device_config_snap (
  id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id                 BIGINT UNSIGNED NOT NULL,
  face_mode                 TINYINT         NOT NULL DEFAULT 1 COMMENT '人脸识别模式 0禁用 1启用',
  certificate_mode          TINYINT         NOT NULL DEFAULT 2 COMMENT '证件识别模式 2=人证+白名单',
  threshold                 INT             NOT NULL DEFAULT 80 COMMENT '人脸阈值 [70,100]',
  man_card_match_threshold  INT             NOT NULL DEFAULT 60 COMMENT '人证阈值 [30,100]',
  recog_space_time          INT             NOT NULL DEFAULT 2 COMMENT '识别去重秒',
  recog_relay               TINYINT         NOT NULL DEFAULT 1 COMMENT '识别即开门',
  closing_delay             INT             NOT NULL DEFAULT 3 COMMENT '磁吸门开门时长秒',
  raw_json                  JSON                     DEFAULT NULL COMMENT '完整参数原样留存',
  version                   INT             NOT NULL DEFAULT 1,
  applied_at                DATETIME(3)              DEFAULT NULL COMMENT '设备回执成功时间',
  created_at                DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_device (device_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备参数快照';

-- ---------------------------------------------------------------------
-- 2. 员工与人脸底库
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS employee;
CREATE TABLE employee (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  person_id     VARCHAR(32)     NOT NULL COMMENT '设备人员ID：E+工号',
  emp_no        VARCHAR(32)     NOT NULL COMMENT '工号',
  emp_name      VARCHAR(64)     NOT NULL,
  dept_name     VARCHAR(64)              DEFAULT NULL,
  leader_emp_no VARCHAR(32)              DEFAULT NULL COMMENT '直属上级工号（备查）',
  mobile_hash   CHAR(64)                 DEFAULT NULL COMMENT 'HMAC-SHA256，用于审批人匹配',
  mobile_enc    VARBINARY(256)           DEFAULT NULL,
  mobile_mask   VARCHAR(20)              DEFAULT NULL COMMENT '展示用 138****1234',
  id_no_hash    CHAR(64)                 DEFAULT NULL,
  id_no_enc     VARBINARY(256)           DEFAULT NULL,
  id_no_mask    VARCHAR(24)              DEFAULT NULL,
  valid_from    DATE                     DEFAULT NULL,
  valid_to      DATE                     DEFAULT NULL COMMENT '到期前 15 天提醒复核',
  status        TINYINT         NOT NULL DEFAULT 1 COMMENT '1在职 0离职/停用',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_person_id (person_id),
  UNIQUE KEY uk_emp_no (emp_no),
  UNIQUE KEY uk_mobile (mobile_hash),
  KEY idx_status (status),
  KEY idx_valid (valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工（白名单，刷脸通行）';

DROP TABLE IF EXISTS employee_face;
CREATE TABLE employee_face (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id BIGINT UNSIGNED NOT NULL,
  object_key  VARCHAR(255)    NOT NULL COMMENT '对象存储 key（私有桶）',
  file_size   INT             NOT NULL DEFAULT 0,
  width       INT             NOT NULL DEFAULT 0,
  height      INT             NOT NULL DEFAULT 0,
  sha256      CHAR(64)        NOT NULL COMMENT '照片摘要，参与版本计算',
  is_current  TINYINT         NOT NULL DEFAULT 1 COMMENT '1当前生效照片',
  uploaded_by BIGINT UNSIGNED NOT NULL COMMENT '上传人=审核人',
  uploaded_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_emp_current (employee_id, is_current)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工人脸照片（PC 端维护，上传即审核）';

-- ---------------------------------------------------------------------
-- 3. 访客与证件
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS visitor;
CREATE TABLE visitor (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id      BIGINT UNSIGNED NOT NULL DEFAULT 1,
  person_id      VARCHAR(32)     NOT NULL COMMENT 'V+yyyyMMdd+4位序号',
  visitor_name   VARCHAR(64)     NOT NULL,
  company        VARCHAR(128)             DEFAULT NULL,
  mobile_hash    CHAR(64)                 DEFAULT NULL,
  mobile_enc     VARBINARY(256)           DEFAULT NULL,
  mobile_mask    VARCHAR(20)              DEFAULT NULL,
  first_visit_at DATETIME(3)              DEFAULT NULL,
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_visitor_person (person_id),
  KEY idx_mobile (mobile_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访客主档（不采集人脸）';

DROP TABLE IF EXISTS visitor_cert;
CREATE TABLE visitor_cert (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  visitor_id  BIGINT UNSIGNED NOT NULL,
  id_type     TINYINT         NOT NULL DEFAULT 1 COMMENT '1身份证',
  id_no_hash  CHAR(64)        NOT NULL COMMENT 'HMAC-SHA256，唯一性判断',
  id_no_enc   VARBINARY(256)  NOT NULL,
  id_no_mask  VARCHAR(24)     NOT NULL,
  valid_from  DATE                     DEFAULT NULL,
  valid_to    DATE                     DEFAULT NULL,
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert (id_no_hash),
  KEY idx_visitor (visitor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访客证件（仅证件号用于白名单匹配）';

DROP TABLE IF EXISTS blacklist;
CREATE TABLE blacklist (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT UNSIGNED NOT NULL DEFAULT 1,
  id_no_hash  CHAR(64)                 DEFAULT NULL,
  id_no_mask  VARCHAR(24)              DEFAULT NULL,
  mobile_hash CHAR(64)                 DEFAULT NULL,
  reason      VARCHAR(255)    NOT NULL,
  valid_from  DATETIME(3)              DEFAULT NULL,
  valid_to    DATETIME(3)              DEFAULT NULL,
  status      TINYINT         NOT NULL DEFAULT 1 COMMENT '1生效 0失效',
  created_by  BIGINT UNSIGNED          DEFAULT NULL,
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_cert (id_no_hash, status),
  KEY idx_mobile (mobile_hash, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='黑名单';

-- ---------------------------------------------------------------------
-- 4. 审批
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS approval_config;
CREATE TABLE approval_config (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id             BIGINT UNSIGNED NOT NULL DEFAULT 1,
  approve_level         TINYINT         NOT NULL DEFAULT 1 COMMENT '1 或 2',
  default_l1_approver_id BIGINT UNSIGNED         DEFAULT NULL COMMENT '手机号匹配不到时的默认一级审批人',
  backup_l1_approver_id BIGINT UNSIGNED          DEFAULT NULL COMMENT '预留：超时升级（本期不启用）',
  l2_candidate_ids      JSON                     DEFAULT NULL COMMENT '[employeeId,...]',
  backup_l2_approver_id BIGINT UNSIGNED          DEFAULT NULL,
  remind_after_minutes  INT             NOT NULL DEFAULT 30 COMMENT '催办间隔',
  escalate_after_minutes INT            NOT NULL DEFAULT 0 COMMENT '0=不启用超时升级',
  invite_max_days       INT             NOT NULL DEFAULT 7 COMMENT '代邀有效期上限',
  updated_by            BIGINT UNSIGNED          DEFAULT NULL,
  updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批配置（全局单行）';

DROP TABLE IF EXISTS appointment;
CREATE TABLE appointment (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id         BIGINT UNSIGNED NOT NULL DEFAULT 1,
  appointment_no    VARCHAR(32)     NOT NULL COMMENT 'A+yyyyMMdd+5位序号',
  visitor_id        BIGINT UNSIGNED NOT NULL COMMENT '主访客',
  primary_person_id VARCHAR(32)     NOT NULL COMMENT '主访客 person_id',
  source            TINYINT         NOT NULL DEFAULT 1 COMMENT '1访客自约 2员工代邀 3现场登记',
  invited_by        BIGINT UNSIGNED          DEFAULT NULL COMMENT '代邀员工 employee.id',
  consent_at        DATETIME(3)              DEFAULT NULL COMMENT '代邀时的同意留痕',
  host_mobile_hash  CHAR(64)                 DEFAULT NULL COMMENT '访客填写的被访人手机摘要',
  host_mobile_mask  VARCHAR(20)              DEFAULT NULL COMMENT '展示用',
  host_employee_id  BIGINT UNSIGNED          DEFAULT NULL COMMENT '匹配到的员工',
  l1_approver_id    BIGINT UNSIGNED          DEFAULT NULL,
  l2_approver_id    BIGINT UNSIGNED          DEFAULT NULL COMMENT '一级通过时确定',
  current_level     TINYINT         NOT NULL DEFAULT 0,
  approval_status   TINYINT         NOT NULL DEFAULT 0 COMMENT '0草稿 1待审 2通过 3驳回 4撤回 5过期 6撤销',
  status            TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审批 1已通过 2下发中 3可通行 4已签到 5已签离 6已过期 7已取消 8已撤销',
  area_id           BIGINT UNSIGNED          DEFAULT NULL COMMENT '本期固定为访客大门',
  visit_start       DATETIME(3)     NOT NULL,
  visit_end         DATETIME(3)     NOT NULL,
  purpose           VARCHAR(255)             DEFAULT NULL,
  company           VARCHAR(128)             DEFAULT NULL,
  approved_at       DATETIME(3)              DEFAULT NULL,
  cancelled_at      DATETIME(3)              DEFAULT NULL,
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt_no (appointment_no),
  KEY idx_status (status, visit_start),
  KEY idx_host_emp (host_employee_id),
  KEY idx_l1 (l1_approver_id),
  KEY idx_l2 (l2_approver_id),
  KEY idx_visit (visit_start, visit_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约单';

DROP TABLE IF EXISTS appointment_visitor;
CREATE TABLE appointment_visitor (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id  BIGINT UNSIGNED NOT NULL,
  person_id       VARCHAR(32)     NOT NULL,
  visitor_name    VARCHAR(64)     NOT NULL,
  id_no_hash      CHAR(64)        NOT NULL,
  id_no_enc       VARBINARY(256)  NOT NULL,
  id_no_mask      VARCHAR(24)     NOT NULL,
  is_primary      TINYINT         NOT NULL DEFAULT 0,
  sync_state      TINYINT         NOT NULL DEFAULT 0 COMMENT '0待下发 1已下发 2失败',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt_person (appointment_id, person_id),
  KEY idx_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约人员（主访客 + 同行人）';

DROP TABLE IF EXISTS approval_task;
CREATE TABLE approval_task (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id  BIGINT UNSIGNED NOT NULL,
  approve_level   TINYINT         NOT NULL COMMENT '1 或 2',
  seq             INT             NOT NULL DEFAULT 1,
  approver_id     BIGINT UNSIGNED NOT NULL,
  status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0待处理 1通过 2驳回 3跳过 4已转办 5失效',
  auto_approved   TINYINT         NOT NULL DEFAULT 0 COMMENT '代邀时一级自动通过=1',
  escalated_from  BIGINT UNSIGNED          DEFAULT NULL,
  acted_at        DATETIME(3)              DEFAULT NULL,
  approve_comment VARCHAR(500)             DEFAULT NULL,
  channel         VARCHAR(16)              DEFAULT NULL COMMENT 'mini/pc/invite/system',
  notify_count    INT             NOT NULL DEFAULT 0,
  last_notified_at DATETIME(3)             DEFAULT NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_appt (appointment_id, approve_level),
  KEY idx_approver (approver_id, status),
  KEY idx_pending (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批任务（留痕，不可省略）';

-- ---------------------------------------------------------------------
-- 5. 凭证
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS credential;
CREATE TABLE credential (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id BIGINT UNSIGNED NOT NULL,
  person_id      VARCHAR(32)     NOT NULL,
  code           VARCHAR(128)    NOT NULL COMMENT '二维码内容（非纯数字，长度>=16）',
  code_hash      CHAR(64)        NOT NULL,
  valid_from     DATETIME(3)     NOT NULL,
  exp_at         DATETIME(3)     NOT NULL,
  max_use        INT             NOT NULL DEFAULT 0 COMMENT '0=时段内不限次',
  use_count      INT             NOT NULL DEFAULT 0,
  status         TINYINT         NOT NULL DEFAULT 1 COMMENT '1有效 2已撤销 3已用尽',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code_hash),
  KEY idx_appt (appointment_id),
  KEY idx_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通行凭证（静态签名码）';

-- ---------------------------------------------------------------------
-- 6. 名单下发
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sync_batch;
CREATE TABLE sync_batch (
  batch_id           CHAR(36)        NOT NULL,
  tenant_id          BIGINT UNSIGNED NOT NULL DEFAULT 1,
  dev_sno            VARCHAR(32)     NOT NULL,
  kind               TINYINT         NOT NULL COMMENT '1全量 2增量 3清理',
  person_type        TINYINT         NOT NULL COMMENT '1访客 4员工',
  total              INT             NOT NULL DEFAULT 0,
  cursor             INT             NOT NULL DEFAULT 0,
  status             TINYINT         NOT NULL DEFAULT 0 COMMENT '0待发 1进行中 2完成 3失败 4作废',
  dirty              TINYINT         NOT NULL DEFAULT 0 COMMENT '执行期间名单有变，需重跑',
  priority           TINYINT         NOT NULL DEFAULT 2 COMMENT '0实时 1增量 2批量 3清理',
  snapshot_expire_at DATETIME(3)              DEFAULT NULL,
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at        DATETIME(3)              DEFAULT NULL,
  PRIMARY KEY (batch_id),
  KEY idx_dev_status (dev_sno, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下发批次';

DROP TABLE IF EXISTS sync_batch_item;
CREATE TABLE sync_batch_item (
  batch_id     CHAR(36)      NOT NULL,
  seq          INT           NOT NULL,
  person_id    VARCHAR(32)   NOT NULL,
  payload_json JSON          NOT NULL COMMENT '下发内容快照，设备回拉只读此处',
  PRIMARY KEY (batch_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下发批次快照';

DROP TABLE IF EXISTS person_sync_state;
CREATE TABLE person_sync_state (
  dev_sno        VARCHAR(32)  NOT NULL,
  person_id      VARCHAR(32)  NOT NULL,
  person_type    TINYINT      NOT NULL COMMENT '1访客 4员工',
  version        CHAR(32)     NOT NULL COMMENT '内容版本，未变则不重下发',
  state          TINYINT      NOT NULL DEFAULT 0 COMMENT '0待下发 1下发中 2已下发 3待删除 4失败 5废弃',
  retry_count    INT          NOT NULL DEFAULT 0,
  last_error     VARCHAR(500)          DEFAULT NULL,
  last_synced_at DATETIME(3)           DEFAULT NULL,
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dev_sno, person_id),
  KEY idx_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每设备每人员的收敛状态（Desired State）';

DROP TABLE IF EXISTS device_cmd;
CREATE TABLE device_cmd (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dev_sno       VARCHAR(32)     NOT NULL,
  method        VARCHAR(32)     NOT NULL COMMENT 'sync_person/sync_card/delete_person/...',
  payload_json  JSON            NOT NULL,
  cmd_id        CHAR(36)        NOT NULL COMMENT '回执幂等键，放入 params.cmdId',
  priority      TINYINT         NOT NULL DEFAULT 2 COMMENT '0实时 1增量 2批量 3清理',
  batch_id      CHAR(36)                 DEFAULT NULL,
  status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待发 1已发 2已回执 3超时 4失败 5废弃',
  retry_count   INT             NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3)              DEFAULT NULL,
  error         VARCHAR(500)             DEFAULT NULL,
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at       DATETIME(3)              DEFAULT NULL,
  acked_at      DATETIME(3)              DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cmd_id (cmd_id),
  KEY idx_pick (dev_sno, status, priority, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指令队列（每设备串行）';

DROP TABLE IF EXISTS device_person_actual;
CREATE TABLE device_person_actual (
  dev_sno    VARCHAR(32) NOT NULL,
  person_id  VARCHAR(32) NOT NULL,
  id_no_mask VARCHAR(24)          DEFAULT NULL,
  synced_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dev_sno, person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备实际人员快照（对账用 Actual State）';

-- ---------------------------------------------------------------------
-- 7. 通行
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS access_event;
CREATE TABLE access_event (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id       VARCHAR(64)     NOT NULL COMMENT '幂等键（设备流水号/MAC+时间）',
  dev_sno        VARCHAR(32)     NOT NULL,
  area_id        BIGINT UNSIGNED          DEFAULT NULL,
  entry_source   TINYINT         NOT NULL DEFAULT 1 COMMENT '1设备记录上报 2在线比对 3二维码在线识别',
  verify_type    TINYINT         NOT NULL COMMENT '1人证 2人脸 3刷卡 4扫码 5陌生人',
  person_id      VARCHAR(32)              DEFAULT NULL,
  person_name    VARCHAR(64)              DEFAULT NULL,
  person_type    TINYINT                  DEFAULT NULL COMMENT '1访客 4员工',
  id_no_mask     VARCHAR(24)              DEFAULT NULL,
  result         TINYINT         NOT NULL COMMENT '1通过 0失败',
  score          DECIMAL(5,2)             DEFAULT NULL COMMENT '比对分值',
  captured_at    DATETIME(3)     NOT NULL COMMENT '设备侧识别时间',
  received_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  cost_ms        INT                      DEFAULT NULL,
  appointment_id BIGINT UNSIGNED          DEFAULT NULL,
  raw_json       JSON                     DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event (event_id),
  KEY idx_dev_time (dev_sno, captured_at),
  KEY idx_person_time (person_id, captured_at),
  KEY idx_appt (appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通行事件（三入口统一落库）';

DROP TABLE IF EXISTS access_event_image;
CREATE TABLE access_event_image (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  access_event_id BIGINT UNSIGNED NOT NULL,
  img_type        TINYINT         NOT NULL COMMENT '1现场抓拍 2证件照 3模板照',
  object_key      VARCHAR(255)    NOT NULL,
  file_size       INT                      DEFAULT NULL,
  expire_at       DATE                     DEFAULT NULL COMMENT '默认 30 天',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_event (access_event_id),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通行图片（私有桶）';

DROP TABLE IF EXISTS visit_record;
CREATE TABLE visit_record (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id      BIGINT UNSIGNED NOT NULL,
  person_id           VARCHAR(32)     NOT NULL,
  check_in_at         DATETIME(3)              DEFAULT NULL,
  check_in_event_id   BIGINT UNSIGNED          DEFAULT NULL,
  check_out_at        DATETIME(3)              DEFAULT NULL,
  check_out_event_id  BIGINT UNSIGNED          DEFAULT NULL,
  check_out_operator  BIGINT UNSIGNED          DEFAULT NULL COMMENT '手工签离操作人',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt_person (appointment_id, person_id),
  KEY idx_checkin (check_in_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='到访记录（签到/签离）';

DROP TABLE IF EXISTS device_heartbeat;
CREATE TABLE device_heartbeat (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dev_sno      VARCHAR(32)     NOT NULL,
  ip           VARCHAR(45)              DEFAULT NULL,
  person_count INT                      DEFAULT NULL,
  reported_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_dev_time (dev_sno, reported_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备心跳（在线率/时钟漂移分析）';

-- ---------------------------------------------------------------------
-- 8. 账号、审计与通知
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  username      VARCHAR(64)     NOT NULL,
  password_hash VARCHAR(128)             DEFAULT NULL,
  employee_id   BIGINT UNSIGNED          DEFAULT NULL COMMENT '关联员工，用于审批身份',
  real_name     VARCHAR(64)              DEFAULT NULL,
  status        TINYINT         NOT NULL DEFAULT 1,
  last_login_at DATETIME(3)              DEFAULT NULL,
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台账号';

DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
  id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  role_code VARCHAR(32)     NOT NULL COMMENT 'ADMIN/VISITOR_MGR/FRONT_DESK/AUDITOR',
  role_name VARCHAR(64)     NOT NULL,
  remark    VARCHAR(255)             DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

DROP TABLE IF EXISTS sys_user_role;
CREATE TABLE sys_user_role (
  user_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色';

DROP TABLE IF EXISTS audit_log;
CREATE TABLE audit_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT UNSIGNED NOT NULL DEFAULT 1,
  user_id     BIGINT UNSIGNED          DEFAULT NULL,
  user_name   VARCHAR(64)              DEFAULT NULL,
  action      VARCHAR(64)     NOT NULL COMMENT 'LOGIN/APPROVE/REVOKE/CONFIG_CHANGE/EXPORT',
  target_type VARCHAR(32)              DEFAULT NULL,
  target_id   VARCHAR(64)              DEFAULT NULL,
  detail      JSON                     DEFAULT NULL,
  ip          VARCHAR(45)              DEFAULT NULL,
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_time (created_at),
  KEY idx_target (target_type, target_id),
  KEY idx_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志（只增不改）';

DROP TABLE IF EXISTS notification_log;
CREATE TABLE notification_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED          DEFAULT NULL,
  channel       VARCHAR(16)     NOT NULL COMMENT 'sub_msg/cs_msg/pc',
  template_code VARCHAR(64)              DEFAULT NULL,
  biz_type      VARCHAR(32)              DEFAULT NULL,
  biz_id        VARCHAR(64)              DEFAULT NULL,
  status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待发 1成功 2失败',
  error         VARCHAR(255)             DEFAULT NULL,
  sent_at       DATETIME(3)              DEFAULT NULL,
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user (user_id, created_at),
  KEY idx_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知发送记录（订阅消息配额管理依据）';

DROP TABLE IF EXISTS pc_login_ticket;
CREATE TABLE pc_login_ticket (
  ticket       CHAR(32)        NOT NULL,
  employee_id  BIGINT UNSIGNED          DEFAULT NULL,
  status       TINYINT         NOT NULL DEFAULT 0 COMMENT '0待扫 1已确认 2已使用 3已过期',
  expire_at    DATETIME(3)     NOT NULL,
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  confirmed_at DATETIME(3)              DEFAULT NULL,
  PRIMARY KEY (ticket),
  KEY idx_expire (expire_at),
  KEY idx_emp (employee_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PC 扫码登录票据';

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 初始化数据
-- =====================================================================
INSERT INTO sys_config (tenant_id, cfg_key, cfg_value, remark) VALUES
 (1, 'credential.advance_minutes', '30',  '码有效期提前量（分钟）'),
 (1, 'credential.delay_minutes',   '60',  '码有效期延后量（分钟）'),
 (1, 'credential.max_use',         '0',   '码最大使用次数，0=时段内不限'),
 (1, 'image.retain_days',          '30',  '抓拍/证件照保留天数'),
 (1, 'record.retain_months',       '12',  '通行与审批记录保留月数'),
 (1, 'cleanup.expired_days',       '7',   '过期访客在设备上保留天数'),
 (1, 'device.capacity_warn_ratio', '0.8', '设备人员数告警水位'),
 (1, 'batch.size.person',          '10',  '员工下发每批人数'),
 (1, 'batch.size.card',            '100', '访客下发每批人数'),
 (1, 'pc.token.hours',             '8',   'PC 登录态时长');

INSERT INTO sys_role (role_code, role_name, remark) VALUES
 ('ADMIN',        '系统管理员', '设备、参数、账号、审计'),
 ('VISITOR_MGR',  '访客管理员', '员工与底库、黑名单、报表'),
 ('FRONT_DESK',   '前台/保安',  '现场登记、签离、人工开门，无审批权'),
 ('AUDITOR',      '审计员',     '只读全量日志');
