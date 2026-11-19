-- 多门点支持：门点排序/备注、设备待分配门点、预约选门
-- 背景：厂区有 4 个门，原实现把门点写死为 gate_id=1，另外 3 个门无法接入。

ALTER TABLE gate
    ADD COLUMN sort_no INT NOT NULL DEFAULT 0 COMMENT '显示顺序，越小越前' AFTER status,
    ADD COLUMN remark VARCHAR(255) NULL COMMENT '备注' AFTER sort_no;

-- 「待分配」门点：轮询式终端首次心跳自动登记时先放这里，
-- 避免新设备默认落到 1 号门、把别的门的名单下发错地方。
-- gate_type=9 专门标记，访客端选门时排除。
INSERT INTO gate (gate_code, gate_name, gate_type, status, sort_no, remark)
VALUES ('UNASSIGNED', '待分配', 9, 1, 999, '设备自动登记时的临时门点，请在设备管理里分配到真实门点');

-- 预约可选门点：NULL 表示沿用 fangke.gate.default-id（兼容历史单据与单门部署）
ALTER TABLE appointment
    ADD COLUMN gate_id BIGINT UNSIGNED NULL COMMENT '预约门点，NULL=走默认门点' AFTER department,
    ADD KEY idx_appointment_gate (gate_id);
