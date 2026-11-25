-- 字段级加固（2026-11-26 巡检）：
-- 1) 相机抓拍图外链可能很长（OSS 带签名），512 会截断/写失败导致整条记录丢失 → 放宽到 1024
-- 2) raw_json 原来是 JSON 列，相机会话里任何一条不合法报文都会让 INSERT 失败（STRICT 模式）
--    → 改 LONGTEXT，原文照存，不参与校验
-- 3) confidence 注释与真实取值对齐：相机 confid 是 0-1000，入库前折算成 0-100
-- 4) 通行记录按「结果 + 人 + 时间」查得比较多，补一个复合索引
ALTER TABLE vehicle_event
    MODIFY COLUMN photo_url  VARCHAR(1024) NULL COMMENT '抓拍图外链（相机上报）',
    MODIFY COLUMN raw_json   LONGTEXT      NULL COMMENT '原始报文（不做 JSON 校验，避免坏报文丢记录）',
    MODIFY COLUMN confidence INT           NULL COMMENT '识别置信度 0-100（相机 0-1000 入库前折算）',
    ADD KEY idx_vehicle_result (result_status, person_no, event_time);
