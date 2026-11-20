-- 身份证改为选填：访客只填姓名/手机号也能预约（刷证通行仍需要证件，故保留字段但不强制）
-- MySQL 唯一索引允许多个 NULL，因此 id_card_hmac 置空不会互相冲突。
ALTER TABLE visitor
    MODIFY COLUMN id_card_enc VARBINARY(255) NULL COMMENT '身份证号密文（选填：未填时为 NULL）',
    MODIFY COLUMN id_card_hmac CHAR(64) NULL COMMENT '身份证号 HMAC（选填，唯一）',
    MODIFY COLUMN id_card_mask VARCHAR(32) NULL COMMENT '身份证号脱敏（选填）';
