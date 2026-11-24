-- C-75 区域与访客/预约关联：预约单绑定来访区域（visit_dict AREA）
ALTER TABLE appointment
    ADD COLUMN area_id BIGINT UNSIGNED NULL COMMENT '来访区域（visit_dict AREA）' AFTER purpose,
    ADD KEY idx_area (area_id);
