-- 车辆历史数据补人员编号（C-69 收尾）
-- V16 时代访客车辆记的是 appointment.id（不是 visitor.id），V17 的回填因此漏掉这部分。
-- 这里按 appointment → visitor 再补一次；新数据已由 VehicleAccessService 直接写 visitor.person_no。
UPDATE vehicle_event v
    JOIN appointment a ON v.matched_type = 2 AND a.id = v.matched_id
    JOIN visitor vi ON vi.id = a.visitor_id
SET v.person_no = vi.person_no,
    v.matched_id = vi.id
WHERE v.person_no IS NULL;
