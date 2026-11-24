package com.fangke.visitor.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 车闸相机通道（{@link Device.Channel#VEHICLE}）——**接口预留，厂商协议待接入**（docs/18）。
 *
 * <p>车闸相机与 YC-P208 不是一个牌子，能力模型完全不同：
 * <ul>
 *   <li>它不认"人员名单"，只认<b>车牌名单</b>（车牌来自员工白名单与访客预约）；</li>
 *   <li>它把识别到的车牌推给平台，由平台判定后回 {@code openGate}（{@code /openapi/v1/vehicleEvent}，已实现）；</li>
 *   <li>部分厂商还要求平台把车牌白名单下发到相机本地（断网也能放行）。</li>
 * </ul>
 *
 * <p>所以这里只声明"车闸应有的能力"，具体 HTTP 报文等拿到厂商文档后填
 * {@link #pushPlates} / {@link #removePlate} 即可，业务代码不用改。
 * 在此之前任何人员名单动作都会被明确拒绝，避免把人脸/身份证名单误推给车闸相机。
 */
@Component
public class VehicleCameraAdapter implements DeviceAdapter {

    /** 厂商协议未接入时的统一提示，现场看这句话就知道该去找厂商要文档。 */
    public static final String PENDING_VENDOR = "车闸相机厂商协议未接入：请提供车牌名单下发接口文档后再启用";

    @Override
    public boolean supports(Device.Channel channel) {
        return channel == Device.Channel.VEHICLE;
    }

    @Override
    public Set<DeviceCapability> capabilities() {
        // 车牌识别上报走 /openapi/v1/vehicleEvent，已经能用；车牌名单下发要等厂商协议
        return EnumSet.of(
                DeviceCapability.PLATE_CALLBACK,
                DeviceCapability.RECORD_CALLBACK,
                DeviceCapability.HEARTBEAT);
    }

    @Override
    public void pushPlates(Device device, List<com.fangke.visitor.device.model.PlatePayload> plates) {
        throw new DeviceException(ErrorCode.DEVICE_UNSUPPORTED,
                "设备 " + device.getDeviceSn() + " 的车牌名单下发待接入：" + PENDING_VENDOR);
    }

    @Override
    public void removePlate(Device device, String plateNo) {
        throw new DeviceException(ErrorCode.DEVICE_UNSUPPORTED,
                "设备 " + device.getDeviceSn() + " 的车牌删除待接入：" + PENDING_VENDOR);
    }

    /* ---------------- 人行名单：车闸相机一概不支持 ---------------- */

    @Override
    public JsonNode readParams(Device device) {
        throw unsupported(device, "读取参数");
    }

    @Override
    public void writeParams(Device device, Map<String, Object> partial) {
        throw unsupported(device, "下发参数");
    }

    @Override
    public void upsertPerson(Device device, PersonPayload payload) {
        throw unsupported(device, "下发人员名单（车闸只认车牌，请走车牌名单）");
    }

    @Override
    public void removePerson(Device device, String personNo, String userType) {
        throw unsupported(device, "删除人员名单（车闸只认车牌）");
    }

    @Override
    public void clearAllPersons(Device device) {
        throw unsupported(device, "清空名单");
    }

    @Override
    public List<String> listPersonNos(Device device) {
        throw unsupported(device, "读取名单");
    }

    @Override
    public JsonNode readVersion(Device device) {
        throw unsupported(device, "读取版本");
    }

    @Override
    public void openDoor(Device device) {
        throw unsupported(device, "远程开闸（车闸由相机识别后开闸，平台不直连道闸控制器）");
    }

    @Override
    public void showMessage(Device device, String message) {
        throw unsupported(device, "屏幕提示");
    }

    @Override
    public void setDeviceTime(Device device, LocalDateTime time) {
        throw unsupported(device, "时间校准");
    }

    private DeviceException unsupported(Device device, String action) {
        return new DeviceException(ErrorCode.DEVICE_UNSUPPORTED,
                "设备 " + device.getDeviceSn() + " 是车闸相机，不支持" + action + "：" + PENDING_VENDOR);
    }
}
