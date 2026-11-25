-- 远程管理改「心跳下发」：设备参数由平台期望值驱动，设备心跳拉参数时合并下发（C-85）。
-- 只存白名单键（三个安全开关 + thirdOpenDoorLocal），thirdQrUrl 仍由平台按配置实时计算。
ALTER TABLE device
  ADD COLUMN param_overrides JSON NULL COMMENT '平台侧期望的设备参数覆盖（白名单键），syncDeviceParameter 时合并下发';
