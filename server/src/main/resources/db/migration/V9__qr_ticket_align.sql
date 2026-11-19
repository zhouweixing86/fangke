-- 对齐 docs/10 §9 与 BR-V-020：
-- 1) 60 秒有效期内**可重复使用**（通行后不作废），因此记录 use_count / last_used_at；
-- 2) 只存码摘要 code_hash，不存明文码（凭据最小暴露）；
-- 3) status：1 有效 / 2 已过期 / 3 已撤销。
DROP TABLE IF EXISTS qr_ticket;
CREATE TABLE qr_ticket (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id  BIGINT UNSIGNED NOT NULL,
  appointment_no  VARCHAR(32) NOT NULL,
  nonce           VARCHAR(16) NOT NULL COMMENT '码内随机串，唯一',
  code_hash       CHAR(64) NOT NULL COMMENT '完整码的 SHA256（不存明文）',
  status          TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 2已过期 3已撤销',
  use_count       INT NOT NULL DEFAULT 0 COMMENT '60 秒内可重复使用（BR-V-020）',
  issued_channel  VARCHAR(16) NOT NULL COMMENT 'ADMIN / GUARD',
  issued_by       BIGINT UNSIGNED NULL,
  issued_at       DATETIME(3) NOT NULL,
  expire_at       DATETIME(3) NOT NULL,
  last_used_at    DATETIME(3) NULL,
  verified_by     BIGINT UNSIGNED NULL COMMENT '最近一次核验放行的保安账号',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_nonce (nonce),
  KEY idx_appointment (appointment_id, status),
  KEY idx_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态二维码票据（60 秒内可重复使用、可撤销、校验留痕）';
