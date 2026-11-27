-- C-111：二维码对所有预约默认开通，审批节点不再决定「是否同意二维码开门」。
-- 之前两个 allow_qr 列已无任何逻辑读取，直接删掉，避免留下"看起来能配、实际无效"的开关。
ALTER TABLE approval_node DROP COLUMN allow_qr;

ALTER TABLE appointment_flow DROP COLUMN allow_qr;
