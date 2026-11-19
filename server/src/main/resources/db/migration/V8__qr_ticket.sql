-- M7 动态二维码票据（docs/10 §12 qr_ticket）
-- 一张票 = 一次签发的 60 秒动态码；用于一次性消费、撤销与留痕。
CREATE TABLE qr_ticket (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id  BIGINT UNSIGNED NOT NULL,
  appointment_no  VARCHAR(32) NOT NULL,
  nonce           VARCHAR(16) NOT NULL COMMENT '码内随机串，唯一',
  code            VARCHAR(160) NOT NULL COMMENT '完整码，便于人工核对',
  status          TINYINT NOT NULL DEFAULT 1 COMMENT '1未使用 2已使用 3已撤销 4已过期',
  issued_channel  VARCHAR(16) NOT NULL COMMENT 'ADMIN / GUARD',
  issued_by       BIGINT UNSIGNED NULL,
  issued_at       DATETIME(3) NOT NULL,
  expire_at       DATETIME(3) NOT NULL,
  used_at         DATETIME(3) NULL,
  verified_by     BIGINT UNSIGNED NULL COMMENT '核验放行的保安账号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_nonce (nonce),
  KEY idx_appointment (appointment_id, status),
  KEY idx_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态二维码票据（一次性、可撤销）';
