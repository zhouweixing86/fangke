-- 车辆记录补人员编号（C-69）：通行记录按「姓名 + 人员编号」合并行人/车辆两种通道，
-- 车辆事件必须能对上同一个 person_no（员工 E000123 / 访客 V202611190001）。
ALTER TABLE vehicle_event
    ADD COLUMN person_no VARCHAR(20) NULL COMMENT '人员编号（员工 employee.person_no / 访客 visitor.person_no）'
        AFTER matched_name,
    ADD KEY idx_vehicle_person_time (person_no, event_time);

-- 历史数据回填：员工按 matched_id=employee.id，访客按 matched_id=visitor.id
UPDATE vehicle_event v
    JOIN employee e ON v.matched_type = 1 AND e.id = v.matched_id
SET v.person_no = e.person_no
WHERE v.person_no IS NULL;

UPDATE vehicle_event v
    JOIN visitor vi ON v.matched_type = 2 AND vi.id = v.matched_id
SET v.person_no = vi.person_no
WHERE v.person_no IS NULL;
