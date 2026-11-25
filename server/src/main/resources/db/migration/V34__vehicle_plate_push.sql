-- 车闸车牌下发状态（C-86）：与车场开放接口 visitor_sync 的差分对账（docs/19）。
-- 只存「已成功下发到车场」的快照，内容不变就不重复调用；快照里消失的车牌走 operate_type=3 删除。
CREATE TABLE vehicle_plate_push (
  plate_no     VARCHAR(16) NOT NULL,
  visitor_id   VARCHAR(64) NOT NULL COMMENT '车场侧定位键（改/删按它），由平台按车牌稳定生成',
  owner_type   VARCHAR(16) NOT NULL COMMENT 'EMPLOYEE / VISITOR',
  owner_ref    BIGINT UNSIGNED NULL COMMENT '员工 id 或预约 id',
  person_name  VARCHAR(64) NULL,
  begin_time   DATETIME NULL,
  end_time     DATETIME NULL,
  fingerprint  CHAR(64) NOT NULL COMMENT '内容指纹：姓名/有效期变化才重发',
  state        VARCHAR(16) NOT NULL DEFAULT 'PUSHED' COMMENT 'PUSHED 已下发 / FAILED 上次失败',
  last_error   VARCHAR(512) NULL,
  pushed_at    DATETIME(3) NULL,
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (plate_no),
  KEY idx_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车闸车牌下发快照（云车场 visitor_sync）';
