-- 放宽访问来源 UA 字段长度（2026-11-21）
--
-- 实测：微信开发者工具/微信内置浏览器的 User-Agent 长度普遍 300~500 字符，
-- 原 varchar(255) 会触发 "Data too long for column 'ua'"，导致访客提交预约报 500。
-- 这里放宽到 1024，同时代码侧对 ip/ua 做截断兜底，避免再有超长 UA 打挂业务。
ALTER TABLE visitor_consent
    MODIFY COLUMN ua VARCHAR(1024) NULL COMMENT '同意时的 User-Agent（微信 UA 可能 300~500 字符）';
