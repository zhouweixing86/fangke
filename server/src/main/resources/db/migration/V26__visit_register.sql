-- =====================================================================
-- 来访登记 + 基础字典（C-74）：对齐参考站「来访信息 / 事由信息 / 区域信息 / 岗位」
--
-- 说明：参考站是 RuoYi 版访客登记系统，核心就是"登记谁、去哪（区域）、干什么（事由）、
-- 什么时候到/走"。本系统原有 appointment 是预约流程，现场登记(onsite)埋在 App 里；
-- 这里补一套管理端可直接录入的「来访登记」，并把事由/区域/岗位做成可维护字典。
-- =====================================================================

CREATE TABLE visit_dict (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dict_type  VARCHAR(16) NOT NULL COMMENT 'CAUSE 事由 / AREA 区域 / POST 岗位',
  name       VARCHAR(64) NOT NULL,
  sort       INT NOT NULL DEFAULT 0,
  status     TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark     VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_type_name (dict_type, name),
  KEY idx_type_sort (dict_type, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='基础字典（事由/区域/岗位）';

CREATE TABLE visit_register (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  area_id      BIGINT UNSIGNED NULL COMMENT '区域（visit_dict AREA）',
  cause_id     BIGINT UNSIGNED NULL COMMENT '事由（visit_dict CAUSE）',
  visitor_name VARCHAR(64) NOT NULL,
  phone_enc    VARBINARY(255) NULL COMMENT '手机号密文（BR-D-002）',
  phone_mask   VARCHAR(32) NULL,
  plate_no     VARCHAR(16) NULL,
  company      VARCHAR(128) NULL,
  person_count INT NOT NULL DEFAULT 1,
  arrival_time DATETIME NOT NULL,
  leave_time   DATETIME NULL,
  status       TINYINT NOT NULL DEFAULT 1 COMMENT '1在场 2已离开',
  remark       VARCHAR(255) NULL,
  created_by   BIGINT UNSIGNED NULL COMMENT '登记人（后台账号）',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_arrival (arrival_time),
  KEY idx_status (status, arrival_time),
  KEY idx_area (area_id),
  KEY idx_cause (cause_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来访登记（现场录入）';

INSERT INTO visit_dict (dict_type, name, sort, remark) VALUES
  ('CAUSE', '提送货物', 1, NULL),
  ('CAUSE', '洽谈业务', 2, NULL),
  ('CAUSE', '维修施工', 3, NULL),
  ('CAUSE', '其他', 9, NULL);

INSERT INTO visit_dict (dict_type, name, sort, remark) VALUES
  ('AREA', '一号库房', 1, NULL),
  ('AREA', '货场一区', 2, NULL),
  ('AREA', '东门值班室', 3, NULL),
  ('AREA', '南门值班室', 4, NULL);

-- 新模块权限：来访登记（含基础字典）
UPDATE sys_role
SET perms = JSON_ARRAY_APPEND(perms, '$', 'visit:read')
WHERE role_code IN ('GUARD', 'VISITOR_ADMIN')
  AND NOT JSON_CONTAINS(perms, '"visit:read"');
