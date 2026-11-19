-- =====================================================================
-- M2 员工管理：部门、员工、人脸照片（结构同 docs/10 §3）
-- =====================================================================

CREATE TABLE dept (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dept_code  VARCHAR(32) NOT NULL COMMENT '部门编码',
  dept_name  VARCHAR(64) NOT NULL,
  parent_id  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  sort_no    INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dept_code (dept_code),
  KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门';

CREATE TABLE employee (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  person_no     VARCHAR(16) NOT NULL COMMENT '业务编号：E+6位工号',
  emp_no        VARCHAR(16) NOT NULL COMMENT '工号',
  name          VARCHAR(64) NOT NULL,
  dept_id       BIGINT UNSIGNED NULL,
  leader_id     BIGINT UNSIGNED NULL,
  title         VARCHAR(64) NULL,
  phone_enc     VARBINARY(255) NULL,
  phone_hmac    CHAR(64) NULL,
  phone_mask    VARCHAR(20) NULL,
  id_card_enc   VARBINARY(255) NULL,
  id_card_hmac  CHAR(64) NULL,
  id_card_mask  VARCHAR(32) NULL,
  status        TINYINT NOT NULL DEFAULT 1 COMMENT '1在职 2待复核 3停用 4已删除',
  valid_from    DATE NULL,
  valid_to      DATE NULL,
  face_status   TINYINT NOT NULL DEFAULT 0 COMMENT '0无照片 1已上传 2下发中 3已生效',
  remark        VARCHAR(255) NULL,
  created_by    BIGINT UNSIGNED NULL,
  updated_by    BIGINT UNSIGNED NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at    DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_person_no (person_no),
  UNIQUE KEY uk_emp_no (emp_no),
  UNIQUE KEY uk_phone_hmac (phone_hmac),
  UNIQUE KEY uk_id_card_hmac (id_card_hmac),
  KEY idx_status_valid_to (status, valid_to),
  KEY idx_dept (dept_id),
  KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工';

CREATE TABLE employee_face (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id  BIGINT UNSIGNED NOT NULL,
  oss_key      VARCHAR(255) NOT NULL COMMENT '原始上传件',
  oss_key_std  VARCHAR(255) NULL COMMENT '压缩后下发件（100~200KB）',
  width        INT NULL,
  height       INT NULL,
  size_bytes   INT NULL,
  sha256       CHAR(64) NULL,
  is_current   TINYINT(1) NOT NULL DEFAULT 1,
  uploaded_by  BIGINT UNSIGNED NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_emp_current (employee_id, is_current),
  KEY idx_sha256 (sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工人脸照片（二进制在对象存储）';

INSERT INTO dept (dept_code, dept_name, parent_id, sort_no) VALUES
  ('ADM', '行政部', 0, 1),
  ('IT', '信息部', 0, 2),
  ('SEC', '安保部', 0, 3);
