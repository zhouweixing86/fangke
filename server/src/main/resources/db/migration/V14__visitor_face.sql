-- 访客照片（C-66）：访客也能刷脸；不采集则只能刷身份证/扫码
ALTER TABLE visitor
    ADD COLUMN face_oss_key VARCHAR(255) NULL COMMENT '人脸压缩件对象 key（MinIO）' AFTER company,
    ADD COLUMN face_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未采集 1已采集' AFTER face_oss_key;
