-- 车闸 MQTT 通道（C-87）：相机作为客户端连我们云上的 broker，上下行报文都留痕，便于现场排障与协议探测。
CREATE TABLE vehicle_mqtt_log (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  direction  VARCHAR(8) NOT NULL COMMENT 'UP 相机上行 / DOWN 平台下行',
  device_sn  VARCHAR(64) NULL,
  topic      VARCHAR(128) NOT NULL,
  payload    TEXT NULL COMMENT '原始报文（大图 base64 会截断）',
  message    VARCHAR(255) NULL COMMENT '解析/发送结果说明',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_created (created_at),
  KEY idx_device (device_sn, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车闸 MQTT 上下行报文日志';
