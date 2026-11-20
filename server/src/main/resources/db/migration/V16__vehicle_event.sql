-- 车辆通行（C-68）：一期接入车牌识别
-- 判定不另建「车牌授权表」：员工查 employee.plate_no + 在岗 + 有效期，
-- 访客查 appointment.plate_no + 已授权状态 + 当天有效期 + 批准门点集合（appointment_gate.stage=2）。
-- 这里只存车辆通行事件流水。

CREATE TABLE vehicle_event (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_uuid          VARCHAR(80) NOT NULL COMMENT '设备事件唯一号，用于幂等',
  device_sn           VARCHAR(64) NULL COMMENT '车闸/相机设备号',
  gate_id             BIGINT UNSIGNED NULL COMMENT '门点',
  plate_no            VARCHAR(16) NOT NULL COMMENT '车牌号（大写去空格）',
  direction           TINYINT NOT NULL DEFAULT 1 COMMENT '0出 1进，取设备上报',
  event_time          DATETIME(3) NOT NULL,
  confidence          INT NULL COMMENT '识别置信度 0-100',
  matched_type        TINYINT NULL COMMENT '1员工 2访客 NULL=未匹配',
  matched_id          BIGINT UNSIGNED NULL,
  matched_name        VARCHAR(64) NULL,
  result_status       TINYINT NOT NULL COMMENT '1放行 0拒绝',
  fail_reason         VARCHAR(255) NULL,
  photo_key           VARCHAR(255) NULL COMMENT '抓拍图对象 key（可选）',
  raw_json            JSON NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_vehicle_event_uuid (event_uuid),
  KEY idx_vehicle_time (event_time),
  KEY idx_vehicle_plate (plate_no, event_time),
  KEY idx_vehicle_gate (gate_id, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆通行记录';
