package com.fangke.visitor.device.remote;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.pull.PullCommandRepository;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.device.service.DeviceParamOverrides;
import com.fangke.visitor.device.service.DeviceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程管理的「心跳下发」通道（docs/17 §11.2）。
 *
 * <p>轮询式终端（YC-P208 / RV1109）是 HTTP <b>客户端</b>：它能出网来拉指令，但平台连不到它。
 * 所以远程管理的默认实现必须是「入队 → 设备心跳取 action → 设备回执」，而不是平台去 POST 设备 9090：
 * <ul>
 *   <li>云端部署（设备在客户内网、出口是 NAT）也能用；</li>
 *   <li>延迟等于一个心跳周期（当前 5 秒），可接受；</li>
 *   <li>直连 9090 只保留给「同网现场调试」的少数只读能力（读参数、抓拍、弹框…）。</li>
 * </ul>
 *
 * <p>能做的心跳动作来自协议：开门 {@code setDeviceRemoteOpen}、校时 {@code syncDeviceTime}、
 * 参数下发 {@code syncDeviceParameter}（设备回来拉时才生成内容，见 {@link DeviceParamOverrides}）。
 */
@Service
public class DeviceCommandService {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> TYPE_TEXT = Map.of(
            PullCommandRepository.TYPE_OPEN_DOOR, "远程开门",
            PullCommandRepository.TYPE_SYNC_TIME, "校时",
            PullCommandRepository.TYPE_PARAM_SYNC, "参数下发",
            PullCommandRepository.TYPE_ADD_PERSON, "新增名单",
            PullCommandRepository.TYPE_UPDATE_PERSON, "更新名单",
            PullCommandRepository.TYPE_DEL_PERSON, "删除名单");

    private static final Map<Integer, String> STATUS_TEXT = Map.of(
            0, "待执行", 1, "执行中", 2, "成功", 3, "失败", 4, "放弃");

    private final DeviceRepository deviceRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;
    private final DeviceCapabilityService capabilityService;
    private final PullCommandRepository commandRepository;
    private final DeviceRemoteService remoteService;
    private final AppProperties props;

    public DeviceCommandService(DeviceRepository deviceRepository,
                               DeviceAdapterFactory adapterFactory,
                               DeviceGateway gateway,
                               DeviceCapabilityService capabilityService,
                               PullCommandRepository commandRepository,
                               DeviceRemoteService remoteService,
                               AppProperties props) {
        this.deviceRepository = deviceRepository;
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
        this.capabilityService = capabilityService;
        this.commandRepository = commandRepository;
        this.remoteService = remoteService;
        this.props = props;
    }

    /* ---------------- 心跳下发 ---------------- */

    /** 远程开门：入队，下一个心跳生效。 */
    public Map<String, Object> openDoor(Device device) {
        capabilityService.require(device, DeviceCapability.DOOR_OPEN, "远程开门");
        submit(device, "openDoor", adapter -> adapter.openDoor(device));
        return queued(device, PullCommandRepository.TYPE_OPEN_DOOR);
    }

    /** 校时：入队 {@code syncDeviceTime}，设备下一次心跳会把时间调成平台当前时间。 */
    public Map<String, Object> syncTime(Device device) {
        capabilityService.require(device, DeviceCapability.TIME_SYNC, "校时");
        LocalDateTime now = LocalDateTime.now();
        submit(device, "setDeviceTime", adapter -> adapter.setDeviceTime(device, now));
        return queued(device, PullCommandRepository.TYPE_SYNC_TIME);
    }

    /** 参数下发：只入队，内容在设备来拉 {@code syncDeviceParameter} 时生成。 */
    public Map<String, Object> syncParams(Device device) {
        capabilityService.require(device, DeviceCapability.PARAM_WRITE, "参数下发");
        submit(device, "writeParams", adapter -> adapter.writeParams(device, Map.of()));
        return queued(device, PullCommandRepository.TYPE_PARAM_SYNC);
    }

    /** 二维码核验接线：{@code thirdQrUrl} 由平台在参数下发时带上，这里只需触发一次参数同步。 */
    public Map<String, Object> applyQrConfig(Device device) {
        Map<String, Object> view = syncParams(device);
        view.put("qrPushUrl", remoteService.qrPushUrl());
        view.put("thirdOpenDoorLocal", 0);
        return view;
    }

    /**
     * 改设备参数（白名单键）：先存平台期望值，再入队参数下发。
     *
     * <p>设备下一次心跳来拉参数时拿到新值；期望值会一直保留，设备重启/恢复出厂后重新拉参数依然生效。
     */
    public Map<String, Object> setParams(Device device, Map<String, ?> changes) {
        capabilityService.require(device, DeviceCapability.PARAM_WRITE, "参数下发");
        String merged = DeviceParamOverrides.merge(device.getParamOverrides(), changes);
        deviceRepository.updateParamOverrides(device.getId(), merged);
        device.setParamOverrides(merged);
        // 队列只关心「去拉一次参数」，内容在 syncDeviceParameter 时按期望值生成
        submit(device, "writeParams", adapter -> adapter.writeParams(device, Map.of()));
        Map<String, Object> view = queued(device, PullCommandRepository.TYPE_PARAM_SYNC);
        view.put("overrides", DeviceParamOverrides.parse(merged));
        return view;
    }

    /* ---------------- 队列观测 ---------------- */

    /** 队列与设备状态：管理端据此判断「心跳下发是否真的生效」。 */
    public Map<String, Object> status(Device device) {
        int heartSeconds = Math.max(5, props.getDevice().getPullHeartSeconds());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deviceId", device.getId());
        view.put("deviceSn", device.getDeviceSn());
        view.put("online", device.isFreshOnline(heartSeconds));
        view.put("lastHeartbeat", device.getLastHeartbeat() == null
                ? "" : DATETIME.format(device.getLastHeartbeat()));
        view.put("heartSeconds", heartSeconds);
        view.put("pending", commandRepository.countPending(device.getId()));
        view.put("overrides", DeviceParamOverrides.parse(device.getParamOverrides()));
        view.put("allowedParams", new ArrayList<>(DeviceParamOverrides.ALLOWED_KEYS));
        view.put("qrPushUrl", remoteService.qrPushUrl());

        List<Map<String, Object>> commands = new ArrayList<>();
        for (PullCommandRepository.HistoryRow row : commandRepository.listRecent(device.getId(), 10)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("cmdType", row.cmdType());
            item.put("cmdText", TYPE_TEXT.getOrDefault(row.cmdType(), row.cmdType()));
            item.put("status", row.status());
            item.put("statusText", STATUS_TEXT.getOrDefault(row.status(), String.valueOf(row.status())));
            item.put("retryCount", row.retryCount());
            item.put("errorMsg", row.errorMsg() == null ? "" : row.errorMsg());
            item.put("createdAt", row.createdAt() == null ? "" : DATETIME.format(row.createdAt()));
            item.put("finishedAt", row.finishedAt() == null ? "" : DATETIME.format(row.finishedAt()));
            commands.add(item);
        }
        view.put("commands", commands);
        return view;
    }

    /* ---------------- 内部 ---------------- */

    private Map<String, Object> queued(Device device, String cmdType) {
        long pending = commandRepository.countPending(device.getId());
        boolean online = device.isFreshOnline(Math.max(5, props.getDevice().getPullHeartSeconds()));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deviceSn", device.getDeviceSn());
        view.put("cmdType", cmdType);
        view.put("cmdText", TYPE_TEXT.getOrDefault(cmdType, cmdType));
        view.put("queued", true);
        view.put("pending", pending);
        view.put("queuedAt", DATETIME.format(LocalDateTime.now()));
        view.put("hint", online
                ? "已入队，设备下一个心跳（≤" + Math.max(5, props.getDevice().getPullHeartSeconds()) + " 秒）就会取走执行"
                : "已入队，但设备当前没有心跳；设备联网后会自动取走执行");
        return view;
    }

    private void submit(Device device, String describe, java.util.function.Consumer<DeviceAdapter> action) {
        DeviceAdapter adapter = adapterFactory.resolve(device);
        gateway.execute(device, describe, d -> {
            action.accept(adapter);
            return null;
        });
        if (device.getId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "设备未登记，无法下发指令");
        }
    }
}
