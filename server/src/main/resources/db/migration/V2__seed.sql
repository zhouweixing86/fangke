-- =====================================================================
-- P1 种子数据：门点、设备（含 2026-11-16 真机档案）、角色、配置
-- 设备参数基线见 docs/09 §1.1
-- =====================================================================

INSERT INTO gate (id, gate_code, gate_name, gate_type, status)
VALUES (1, 'MAIN_GATE', '公司大门', 1, 1);

-- 入口（真机 YC-P208，已联调）
INSERT INTO device (id, device_sn, mac, device_name, model, firmware, gate_id, direction, channel, ip, port, online_state, capacity, status)
VALUES (1, 'T77R781116ZS', '0A:0C:E8:68:11:16', '大门入口', 'C978GH',
        'V1.11248.20260616-C978GH-DHCP', 1, 1, 'LAN', '192.168.10.135', 8091, 0, 500, 1);

-- 出口（占位，装机后改 SN/MAC/IP）
INSERT INTO device (id, device_sn, mac, device_name, gate_id, direction, channel, ip, port, online_state, capacity, status)
VALUES (2, 'T77R781116ZS-EXIT', '0A:0C:E8:68:11:17', '大门出口', 1, 0, 'LAN', '192.168.10.136', 8091, 0, 500, 1);

INSERT INTO sys_role (role_code, role_name, perms) VALUES
  ('ADMIN', '系统管理员', JSON_ARRAY('*')),
  ('VISITOR_ADMIN', '访客管理员', JSON_ARRAY('employee:*', 'blacklist:*', 'report:read')),
  ('GUARD', '保安', JSON_ARRAY('guard:*'));

INSERT INTO sys_config (config_key, config_value, remark) VALUES
  ('event.media.retain.days', '30', '抓拍/证件照保留天数（BR-D-006）'),
  ('heartbeat.retain.days', '7', '心跳样本保留天数'),
  ('device.default.password', '123456', '设备出厂默认口令'),
  ('oss.presign.expire', '300', '预签名有效期（秒）');
