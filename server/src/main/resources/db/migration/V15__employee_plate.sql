-- 员工车牌号（C-67）：二期车辆识别接入前先把人车关系建起来，后续车牌进厂可直接落到员工进出记录。
ALTER TABLE employee
    ADD COLUMN plate_no VARCHAR(16) NULL COMMENT '车牌号（主车），二期车辆识别用' AFTER title;
