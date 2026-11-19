package com.fangke.visitor.guard.service;

/**
 * 远程开门能力（保安工作台用）。
 */
public interface DoorOpener {

    /**
     * 打开指定门点下的设备。
     *
     * @return 成功开门的设备数量
     * @throws com.fangke.visitor.device.adapter.DeviceException 设备全部失败时抛出
     */
    int open(Long gateId);
}
