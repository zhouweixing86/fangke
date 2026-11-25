package com.fangke.visitor.vehicle.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.audit.AuditLog;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.PlatePayload;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.vehicle.model.VehicleEvent;
import com.fangke.visitor.vehicle.cloud.CloudParkingClient;
import com.fangke.visitor.vehicle.cloud.VehiclePlateCloudService;
import com.fangke.visitor.vehicle.repository.VehicleEventRepository;
import com.fangke.visitor.vehicle.service.VehicleDeviceService;
import com.fangke.visitor.vehicle.service.VehiclePlateService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端车辆通行记录（docs/11 §5.12）。
 */
@RestController
@RequestMapping("/api/admin/vehicle")
public class AdminVehicleController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VehicleEventRepository eventRepository;
    private final GateService gateService;
    private final VehicleDeviceService deviceService;
    private final VehiclePlateService plateService;
    private final VehiclePlateCloudService plateCloudService;
    private final CloudParkingClient cloudClient;
    private final com.fangke.visitor.config.AppProperties props;

    public AdminVehicleController(VehicleEventRepository eventRepository, GateService gateService,
                                  VehicleDeviceService deviceService, VehiclePlateService plateService,
                                  VehiclePlateCloudService plateCloudService, CloudParkingClient cloudClient,
                                  com.fangke.visitor.config.AppProperties props) {
        this.eventRepository = eventRepository;
        this.gateService = gateService;
        this.deviceService = deviceService;
        this.plateService = plateService;
        this.plateCloudService = plateCloudService;
        this.cloudClient = cloudClient;
        this.props = props;
    }

    /**
     * 车闸相机列表（可单独管理，docs/18）。
     *
     * <p>与 {@code /api/admin/device?channel=VEHICLE} 等价，单独开一个口子是为了让
     * 「车闸管理」页面不用关心人行终端。
     */
    @GetMapping("/device")
    public ApiResponse<List<Map<String, Object>>> devices(
            @RequestParam(required = false) Long gateId) {
        List<Map<String, Object>> list = deviceService.list(gateId).stream().map(this::toDeviceView).toList();
        return ApiResponse.ok(list);
    }

    /**
     * 车牌名单快照：员工白名单 + 当天已授权访客（平台侧"应该下发什么"）。
     *
     * <p>厂商协议未接入前，现场就用这个接口核对车牌是否填对。
     */
    @GetMapping("/plate")
    public ApiResponse<Map<String, Object>> plates(@RequestParam(required = false) Long gateId) {
        List<PlatePayload> plates = plateService.snapshot(gateId);
        List<Map<String, Object>> rows = plates.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plateNo", p.plateNo());
            row.put("ownerType", p.ownerType());
            row.put("ownerTypeText", PlatePayload.OWNER_EMPLOYEE.equals(p.ownerType()) ? "员工" : "访客");
            row.put("ownerName", p.ownerName() == null ? "" : p.ownerName());
            row.put("personNo", p.personNo() == null ? "" : p.personNo());
            row.put("validFrom", p.validFrom() == null ? "" : DATETIME.format(p.validFrom()));
            row.put("validTo", p.validTo() == null ? "" : DATETIME.format(p.validTo()));
            row.put("validToText", plateService.validToText(p.validTo()));
            return row;
        }).toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("summary", plateService.countByOwner(plates));
        view.put("gateId", gateId == null ? 0L : gateId);
        view.put("gateName", gateId == null ? "" : gateService.nameOf(gateId));
        view.put("plates", rows);
        return ApiResponse.ok(view);
    }

    /** 把车牌名单推给车闸相机（预留：厂商协议未接入时返回 PENDING_VENDOR）。 */
    @PostMapping("/plate/sync")
    @AuditLog(module = "VEHICLE", action = "PLATE_SYNC")
    public ApiResponse<VehiclePlateService.SyncResult> syncPlates(
            @RequestParam(required = false) Long gateId) {
        return ApiResponse.ok(plateService.sync(gateId));
    }

    /**
     * 车闸车牌下发状态（云端对账快照，docs/19 §3）：已下发到车场的车牌 + 失败原因。
     *
     * <p>与 {@code /plate} 的区别：{@code /plate} 是「平台期望清单」，这里是「车场实际已收到的」。
     */
    @GetMapping("/plate/push-state")
    public ApiResponse<Map<String, Object>> platePushState() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", plateCloudService.enabled());
        view.put("configured", plateCloudService.configured());
        view.put("baseUrl", props.getVehicle().getCloud().getBaseUrl());
        view.put("parkId", props.getVehicle().getCloud().getParkId());
        view.put("channelId", props.getVehicle().getCloud().getChannelId());
        view.put("syncSeconds", props.getVehicle().getCloud().getSyncMs() / 1000);
        view.put("rows", plateCloudService.stateRows());
        return ApiResponse.ok(view);
    }

    private Map<String, Object> toDeviceView(Device d) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", d.getId());
        view.put("deviceSn", d.getDeviceSn());
        view.put("deviceName", d.getDeviceName());
        view.put("model", d.getModel());
        view.put("gateId", d.getGateId());
        view.put("gateName", gateService.nameOf(d.getGateId()));
        view.put("direction", d.getDirection() == null ? 1 : d.getDirection());
        view.put("directionText", d.getDirection() != null && d.getDirection() == 0 ? "出口" : "入口");
        view.put("channel", d.getChannel() == null ? "" : d.getChannel().name());
        view.put("online", d.isFreshOnline(props.getDevice().getPullHeartSeconds()));
        view.put("lastHeartbeat", d.getLastHeartbeat() == null ? "" : DATETIME.format(d.getLastHeartbeat()));
        view.put("remark", d.getRemark() == null ? "" : d.getRemark());
        return view;
    }

    /**
     * 查车场的通道列表（{@code query_channels}）：联调第一步，用来确认出口道闸的 channelId / deviceSN。
     *
     * <p>返回体原样是车场的 `data[]`（channelId / channelName / channelType 1进2出 / deviceSN…）。
     */
    @PostMapping("/plate/query-channels")
    @AuditLog(module = "VEHICLE", action = "QUERY_CHANNELS")
    public ApiResponse<Map<String, Object>> queryChannels() {
        if (!cloudClient.configured()) {
            throw new com.fangke.visitor.common.BusinessException(
                    com.fangke.visitor.common.ErrorCode.PARAM_INVALID,
                    "车场接口未启用或缺少凭据（fangke.vehicle.cloud.*，见 docs/19 §3.1）");
        }
        return ApiResponse.ok(cloudClient.queryChannels());
    }

    @GetMapping("/event")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String plateNo,
            @RequestParam(required = false) Long gateId,
            @RequestParam(required = false) Integer result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> list = eventRepository
                .search(from, to, plateNo, gateId, result, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toView).toList();
        long total = eventRepository.count(from, to, plateNo, gateId, result);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, list));
    }

    private Map<String, Object> toView(VehicleEvent e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", e.id());
        view.put("plateNo", e.plateNo());
        view.put("gateId", e.gateId() == null ? 0L : e.gateId());
        view.put("gateName", gateService.nameOf(e.gateId()));
        view.put("direction", e.direction());
        view.put("directionText", e.direction() == 0 ? "出场" : "进场");
        view.put("eventTime", DATETIME.format(e.eventTime()));
        view.put("confidence", e.confidence() == null ? 0 : e.confidence());
        view.put("matchedType", e.matchedType());
        view.put("matchedTypeText", VehicleEvent.typeText(e.matchedType()));
        view.put("matchedName", e.matchedName() == null ? "" : e.matchedName());
        view.put("result", e.resultStatus());
        view.put("resultText", e.resultStatus() == VehicleEvent.RESULT_PASS ? "放行" : "拒绝");
        view.put("failReason", e.failReason() == null ? "" : e.failReason());
        view.put("photoKey", e.photoKey() == null ? "" : e.photoKey());
        view.put("deviceSn", e.deviceSn() == null ? "" : e.deviceSn());
        return view;
    }
}
