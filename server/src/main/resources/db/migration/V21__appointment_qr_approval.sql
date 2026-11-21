-- 二维码开门：访客在预约时申请、审批人决定是否同意（2026-11-21）
--
-- 背景：访客刷身份证是主通道，但有时闸机刷证不方便（证件消磁、人证不一致），
-- 需要给指定的访客额外开一条"扫二维码"的通道。这是敏感能力，必须由审批人逐个放行，
-- 不能默认开通，所以拆成"申请（访客勾选）"和"批准（审批人勾选）"两个字段。
ALTER TABLE appointment
    ADD COLUMN qr_requested TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '访客是否申请二维码开门：1 申请' AFTER mate_mode,
    ADD COLUMN qr_approved TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '审批人是否同意二维码开门：1 同意（同意后访客端才能取码）' AFTER qr_requested,
    ADD COLUMN qr_approved_by BIGINT UNSIGNED NULL
        COMMENT '同意二维码开门的审批人（sys_user.id / employee.id 视通道而定）' AFTER qr_approved,
    ADD COLUMN qr_approved_at DATETIME(3) NULL
        COMMENT '同意二维码开门的时间' AFTER qr_approved_by;

-- 已存在的历史预约：不给任何一条默认开通二维码
UPDATE appointment SET qr_requested = 0, qr_approved = 0 WHERE qr_requested IS NULL;
