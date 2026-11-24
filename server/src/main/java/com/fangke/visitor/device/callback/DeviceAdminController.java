package com.fangke.visitor.device.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.gate.service.GateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 管理端设备接口（docs/11 §5.3）：档案维护、远程开门、能力矩阵。
 *
 * <p>只保留轮询式终端（PULL）支持的动作；参数读写/对账/自检/屏幕提示属于已下线的
 * 局域网推模式能力，随九鼎设备一起移除。
 */
@RestController
@RequestMapping("/api/admin/device")
public class DeviceAdminController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DeviceRepository deviceRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;
    private final DeviceCapabilityService capabilityService;
    private final GateService gateService;
    private final com.fangke.visitor.config.AppProperties props;

    public DeviceAdminController(DeviceRepository deviceRepository,
                                 DeviceAdapterFactory adapterFactory,
                                 DeviceGateway gateway,
                                 DeviceCapabilityService capabilityService,
                                 GateService gateService,
                                 com.fangke.visitor.config.AppProperties props) {
        this.deviceRepository = deviceRepository;
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
        this.capabilityService = capabilityService;
        this.gateService = gateService;
        this.props = props;
    }

    /** 设备列表：可按门点/通道过滤；不传 gateId 返回全部（多门点部署必须能看到所有门）。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(name = "gateId", required = false) Long gateId,
            @RequestParam(name = "channel", required = false) String channel) {
        Device.Channel parsed = parseChannel(channel);
        List<Device> devices;
        if (parsed != null) {
            devices = deviceRepository.findActiveByChannel(parsed).stream()
                    .filter(d -> gateId == null || gateId.equals(d.getGateId()))
                    .toList();
        } else {
            devices = gateId == null
                    ? deviceRepository.findAllActive()
                    : deviceRepository.findActiveByGate(gateId);
        }
        List<Map<String, Object>> view = devices.stream().map(this::toView).toList();
        return ApiResponse.ok(view);
    }

    private Device.Channel parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            return Device.Channel.valueOf(channel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "通道只能是 PULL（人行终端）/ VEHICLE（车闸相机）/ MOCK / CLOUD：" + channel);
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(toView(load(id)));
    }

    /**
     * 删除设备档案（登记错了、换下来的旧机器）。
     *
     * <p>在线设备不允许删：那种机器通常正在跑，删了名单/记录就对不上了；先断电或断网再删。
     * 历史通行记录不删（它们挂在 device_sn 上，仍可追溯）。
     */
    @DeleteMapping("/{id}")
    @AuditLog(module = "DEVICE", action = "DELETE")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        Device device = load(id);
        // online_state 只会被心跳置 1、没有任务会置回 0，所以按心跳时间判断更准
        LocalDateTime lastBeat = device.getLastHeartbeat();
        if (lastBeat != null && lastBeat.isAfter(LocalDateTime.now().minusMinutes(5))) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "设备最近 5 分钟还有心跳（" + device.getDeviceSn() + "），请先断电或断网再删除");
        }
        deviceRepository.deleteById(id);
        return ApiResponse.ok(Map.of("deleted", true, "deviceSn", device.getDeviceSn()));
    }

    /**
     * 补/改设备档案（docs/11 §5.3）：名称、型号、出入口方向、所属门点、备注。
     *
     * <p>轮询式终端首次心跳只带 SN 和 IP，会自动登记到「待分配」门点；
     * 现场装好后必须在这里分配到真实门点，否则该门点的名单不会下发到这台设备。
     */
    @PutMapping("/{id}")
    @AuditLog(module = "DEVICE", action = "UPDATE_META")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody DeviceUpdateRequest request) {
        load(id);
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求体不能为空");
        }
        Long gateId = request.gateId();
        if (gateId != null) {
            gateService.requireAssignable(gateId);
        }
        Integer direction = request.direction();
        if (direction != null && direction != 0 && direction != 1 && direction != 2) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "出入口方向只能是 0 出口 / 1 入口 / 2 双向");
        }
        String deviceName = blankToNull(request.deviceName());
        if (deviceName != null && deviceName.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "设备名称最长 64 字");
        }
        String model = blankToNull(request.model());
        if (model != null && model.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "设备型号最长 64 字");
        }
        String remark = blankToNull(request.remark());
        if (remark != null && remark.length() > 255) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "备注最长 255 字");
        }
        deviceRepository.updateMeta(id, deviceName, model, direction, gateId, remark);
        Device.Channel channel = parseChannel(request.channel());
        if (channel != null) {
            deviceRepository.updateChannel(id, channel);
        }
        return ApiResponse.ok(toView(load(id)));
    }

    /** 远程开门：走设备指令（PULL 通道随下一次心跳下发）。 */
    @PostMapping("/{id}/door/open")
    @AuditLog(module = "DEVICE", action = "OPEN_DOOR")
    public ApiResponse<Map<String, Object>> openDoor(@PathVariable Long id) {
        Device device = load(id);
        capabilityService.require(device, DeviceCapability.DOOR_OPEN, "远程开门");
        DeviceAdapter adapter = adapterFactory.resolve(device);
        gateway.execute(device, "openDoor", d -> {
            adapter.openDoor(d);
            return null;
        });
        return ApiResponse.ok(Map.of(
                "opened", true,
                "deviceSn", device.getDeviceSn(),
                "openedAt", DATETIME.format(LocalDateTime.now())));
    }

    /** 能力矩阵：换设备/交接时确认这台机器到底能做什么（docs/12 §2）。 */
    @GetMapping("/{id}/capabilities")
    public ApiResponse<List<Map<String, Object>>> capabilities(@PathVariable Long id) {
        return ApiResponse.ok(capabilityService.describe(load(id)));
    }

    private Device load(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "设备不存在：" + id));
    }

    private Map<String, Object> toView(Device d) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", d.getId() == null ? 0L : d.getId());
        view.put("deviceSn", nullSafe(d.getDeviceSn()));
        view.put("mac", nullSafe(d.getMac()));
        view.put("deviceName", nullSafe(d.getDeviceName()));
        view.put("model", nullSafe(d.getModel()));
        view.put("firmware", nullSafe(d.getFirmware()));
        view.put("direction", d.getDirection() == null ? 1 : d.getDirection());
        view.put("directionText", directionText(d.getDirection()));
        view.put("gateId", d.getGateId() == null ? 0L : d.getGateId());
        view.put("gateName", gateService.nameOf(d.getGateId()));
        view.put("channel", d.getChannel() == null ? "PULL" : d.getChannel().name());
        view.put("channelText", Device.channelText(d.getChannel()));
        view.put("ip", nullSafe(d.getIp()));
        view.put("online", isFresh(d));
        view.put("lastHeartbeat", d.getLastHeartbeat() == null ? "" : DATETIME.format(d.getLastHeartbeat()));
        view.put("personCount", d.getPersonCount() == null ? 0 : d.getPersonCount());
        view.put("capacity", d.getCapacity() == null ? 0 : d.getCapacity());
        view.put("remark", nullSafe(d.getRemark()));
        return view;
    }

    /**
     * 是否"真在线"：设备断电后 online_state 仍为 1，用 {@link Device#isFreshOnline} 按心跳新鲜度判断。
     */
    private boolean isFresh(Device d) {
        return d.isFreshOnline(props.getDevice().getPullHeartSeconds());
    }

    private String blankToNull(String value) {        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 0 出口 / 1 入口 / 2 双向（一个门只有一台车闸或行闸时用双向）。 */
    private String directionText(Integer direction) {
        if (direction == null) {
            return "入口";
        }
        return switch (direction) {
            case 0 -> "出口";
            case 2 -> "双向";
            default -> "入口";
        };
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 设备档案补录请求；字段为 null 表示不改该项。 */
    public record DeviceUpdateRequest(String deviceName, String model, Integer direction,
                                      Long gateId, String remark, String channel) {
    }
}
