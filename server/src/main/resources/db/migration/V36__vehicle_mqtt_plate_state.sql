-- 车闸 MQTT 车牌对账（docs/23）：按「设备 + 车牌」记录已下发指纹，
-- 定时对比平台期望清单 → 新增/更新 AddCarInfo、缺失 DeleteCarInfo。
CREATE TABLE IF NOT EXISTS vehicle_mqtt_plate_state (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    device_sn   VARCHAR(64)  NOT NULL COMMENT '车闸相机 SN',
    plate_no    VARCHAR(32)  NOT NULL COMMENT '车牌（已规范化）',
    fingerprint VARCHAR(128) NOT NULL COMMENT '内容指纹（类型/有效期），变化即重新下发',
    pushed_at   DATETIME     NOT NULL COMMENT '最近一次下发成功时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_plate (device_sn, plate_no),
    KEY idx_device (device_sn)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车闸 MQTT 车牌下发状态';

-- 相机识别结果是直传抓拍图外链（厂商 OSS），与本地对象存储的 photo_key 二选一
ALTER TABLE vehicle_event
    ADD COLUMN photo_url VARCHAR(512) NULL COMMENT '抓拍图外链（相机上报）' AFTER photo_key;
