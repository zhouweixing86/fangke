-- 设备通道规范化：历史数据里的 channel='LAN'（九鼎已下线）在代码里被降级为 PULL，
-- 但按通道过滤是直接查 channel 列，导致「人行终端」页签查不到设备（C-84）。
UPDATE device
SET channel = 'PULL'
WHERE channel IS NULL OR channel NOT IN ('PULL', 'VEHICLE', 'MOCK', 'CLOUD');
