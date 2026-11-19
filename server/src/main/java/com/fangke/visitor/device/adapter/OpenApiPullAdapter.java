package com.fangke.visitor.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;
import com.fangke.visitor.device.pull.PullCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轮询式终端适配器（厂商《HTTP 通讯协议，客户端版》）。
 *
 * <p><b>核心差异</b>：这类设备是 HTTP <b>客户端</b>，平台连不上它，只能"入队"，
 * 由设备按心跳来取指令。因此本适配器里凡是"平台主动读设备"的动作都无法实现，
 * 会抛 {@link ErrorCode#DEVICE_UNSUPPORTED} 并说明原因——这比假装成功更好排查。
 *
 * <p>能力矩阵（对应 docs/12）：
 * <pre>
 * 支持：PERSON_UPSERT / PERSON_REMOVE / DOOR_OPEN / TIME_SYNC / PARAM_WRITE
 *       FACE_PHOTO（详情里带 picData1）/ QR_ONLINE（设备把码推给我们）/ HEARTBEAT / RECORD_CALLBACK
 * 不支持：PERSON_LIST / PERSON_CLEAR / PARAM_READ / VERSION_READ / SCREEN_MESSAGE
 * </pre>
 */
@Component
public class OpenApiPullAdapter implements DeviceAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiPullAdapter.class);

    private final PullCommandRepository commandRepository;

    public OpenApiPullAdapter(PullCommandRepository commandRepository) {
        this.commandRepository = commandRepository;
    }

    @Override
    public boolean supports(Device.Channel channel) {
        return channel == Device.Channel.PULL;
    }

    @Override
    public Set<DeviceCapability> capabilities() {
        return EnumSet.of(
                DeviceCapability.PERSON_UPSERT,
                DeviceCapability.PERSON_REMOVE,
                DeviceCapability.DOOR_OPEN,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.PARAM_WRITE,
                DeviceCapability.FACE_PHOTO,
                DeviceCapability.QR_ONLINE,
                DeviceCapability.HEARTBEAT,
                DeviceCapability.RECORD_CALLBACK);
    }

    /* ---------------- 平台主动读：一律不支持 ---------------- */

    @Override
    public JsonNode readParams(Device device) {
        throw unsupported(device, "读取参数", "该协议只支持平台下发参数，设备不会上报当前参数");
    }

    @Override
    public List<String> listPersonNos(Device device) {
        throw unsupported(device, "读取设备名单", "该协议里名单由设备主动来拉，平台无法反查设备本地名单");
    }

    @Override
    public JsonNode readVersion(Device device) {
        throw unsupported(device, "读取版本", "该协议未定义版本上报接口，请在设备屏幕上查看后登记到设备档案");
    }

    @Override
    public void clearAllPersons(Device device) {
        throw unsupported(device, "清空名单", "该协议未提供清空名单指令，请逐人删除或恢复出厂设置");
    }

    /* ---------------- 通过队列下发 ---------------- */

    @Override
    public void writeParams(Device device, Map<String, Object> partial) {
        // 参数内容在设备请求 /syncDeviceParameter 时生成，这里只入队触发
        enqueue(device, PullCommandRepository.TYPE_PARAM_SYNC, 7, null);
        log.info("参数下发已入队 device={} 字段={}", device.getDeviceSn(), partial.keySet());
    }

    @Override
    public void upsertPerson(Device device, PersonPayload payload) {
        enqueue(device, PullCommandRepository.TYPE_ADD_PERSON, 5, personPayload(payload.getPersonNo()));
        log.info("名单下发已入队 device={} personNo={} valid={}~{}", device.getDeviceSn(),
                payload.getPersonNo(), payload.getValidFrom(), payload.getValidTo());
    }

    @Override
    public void removePerson(Device device, String personNo, String userType) {
        enqueue(device, PullCommandRepository.TYPE_DEL_PERSON, 5, personPayload(personNo));
        log.info("名单删除已入队 device={} personNo={}", device.getDeviceSn(), personNo);
    }

    @Override
    public void openDoor(Device device) {
        // 协议没有回执，随心跳下发即视为完成（见 OpenApiDeviceService#heartbeat）
        enqueue(device, PullCommandRepository.TYPE_OPEN_DOOR, 10, null);
        log.info("远程开门已入队 device={}（下一个心跳生效）", device.getDeviceSn());
    }

    @Override
    public void setDeviceTime(Device device, LocalDateTime time) {
        enqueue(device, PullCommandRepository.TYPE_SYNC_TIME, 8, null);
        log.info("校时已入队 device={} 目标时间={}", device.getDeviceSn(), time);
    }

    @Override
    public void showMessage(Device device, String message) {
        throw unsupported(device, "屏幕提示", "该协议未定义屏幕提示指令（MQTT 版才支持自定义弹框）");
    }

    /* ---------------- 内部 ---------------- */

    private void enqueue(Device device, String cmdType, int priority, String payload) {
        if (device.getId() == null) {
            throw new DeviceException(ErrorCode.DEVICE_PARAM_ERROR,
                    "设备 " + device.getDeviceSn() + " 尚未登记，无法下发指令");
        }
        commandRepository.enqueue(device.getId(), cmdType, priority, payload, null);
    }

    private String personPayload(String personNo) {
        return "{\"personNo\":\"" + personNo + "\"}";
    }

    private DeviceException unsupported(Device device, String action, String hint) {
        return new DeviceException(ErrorCode.DEVICE_UNSUPPORTED,
                "设备 " + device.getDeviceSn() + "（型号 " + nullSafe(device.getModel())
                        + "，通道 PULL）不支持「" + action + "」：" + hint);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
