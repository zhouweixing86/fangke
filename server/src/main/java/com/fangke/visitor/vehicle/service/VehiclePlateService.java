package com.fangke.visitor.vehicle.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentGateRepository;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PlatePayload;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.vehicle.cloud.VehiclePlateCloudService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 车闸车牌名单（docs/18）：把「员工白名单 + 当天已授权访客」的车牌汇总成一份清单，
 * 供车闸相机本地存一份（断网也能放行）。
 *
 * <p>车牌数据本来就存在两个地方，不另建"车牌授权表"：
 * {@code employee.plate_no}（长期）与 {@code appointment.plate_no}（当天 + 批准门点）。
 *
 * <p>下发依赖车闸相机的厂商协议：协议未接入时 {@code PLATE_UPSERT} 能力不成立，
 * 这里只返回 {@code PENDING_VENDOR}，不会把接口调成 500。
 */
@Service
public class VehiclePlateService {

    private static final Logger log = LoggerFactory.getLogger(VehiclePlateService.class);

    /** 一台相机的下发结果。 */
    public record DeviceResult(Long deviceId, String deviceSn, String deviceName, Long gateId,
                               String status, String message, int plateCount) {
    }

    /** 一次下发的汇总。 */
    public record SyncResult(int plateTotal, int pendingDevices, List<DeviceResult> devices,
                             VehiclePlateCloudService.SyncResult cloud) {
    }

    private final EmployeeRepository employeeRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentGateRepository appointmentGateRepository;
    private final VisitorRepository visitorRepository;
    private final VehicleDeviceService vehicleDeviceService;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceCapabilityService capabilityService;
    private final VehiclePlateCloudService cloudService;
    /** 懒加载：MQTT 通道反过来依赖本服务（取车牌快照），直接注入会循环依赖。 */
    private final org.springframework.beans.factory.ObjectProvider<com.fangke.visitor.vehicle.mqtt.VehicleMqttService>
            mqttServiceProvider;

    public VehiclePlateService(EmployeeRepository employeeRepository,
                               AppointmentRepository appointmentRepository,
                               AppointmentGateRepository appointmentGateRepository,
                               VisitorRepository visitorRepository,
                               VehicleDeviceService vehicleDeviceService,
                               DeviceAdapterFactory adapterFactory,
                               DeviceCapabilityService capabilityService,
                               VehiclePlateCloudService cloudService,
                               org.springframework.beans.factory.ObjectProvider<
                                       com.fangke.visitor.vehicle.mqtt.VehicleMqttService> mqttServiceProvider) {
        this.employeeRepository = employeeRepository;
        this.appointmentRepository = appointmentRepository;
        this.appointmentGateRepository = appointmentGateRepository;
        this.visitorRepository = visitorRepository;
        this.vehicleDeviceService = vehicleDeviceService;
        this.adapterFactory = adapterFactory;
        this.capabilityService = capabilityService;
        this.cloudService = cloudService;
        this.mqttServiceProvider = mqttServiceProvider;
    }

    /**
     * 车牌清单快照（同车牌只保留一条，员工优先）。
     *
     * @param gateId 门点；为空则不按门点过滤（看全量）
     */
    public List<PlatePayload> snapshot(Long gateId) {
        Map<String, PlatePayload> byPlate = new LinkedHashMap<>();
        for (Employee employee : employeeRepository.findActiveWithPlate()) {
            String plate = VehicleAccessService.normalize(employee.getPlateNo());
            if (plate.isEmpty()) {
                continue;
            }
            byPlate.putIfAbsent(plate, new PlatePayload(plate, PlatePayload.OWNER_EMPLOYEE, employee.getId(),
                    employee.getName(), employee.getPersonNo(),
                    employee.getValidFrom() == null ? null : employee.getValidFrom().atStartOfDay(),
                    employee.getValidTo() == null ? null : employee.getValidTo().atTime(23, 59, 59)));
        }

        LocalDate today = LocalDate.now();
        List<Appointment> appointments = appointmentRepository.findPlateAppointmentsOnDate(today);
        // 批量取门点与访客姓名：这个快照每 20 秒跑一次，逐单查询会退化成 N+1
        Map<Long, List<Long>> gatesByAppointment = appointmentGateRepository.findGateIds(
                appointments.stream().map(Appointment::getId).filter(Objects::nonNull).toList(),
                AppointmentGateRepository.STAGE_APPROVED);
        Map<Long, String> visitorNames = visitorRepository.findNamesByIds(
                appointments.stream().map(Appointment::getVisitorId).filter(Objects::nonNull).distinct().toList());
        Set<Long> blacklistedVisitors = visitorRepository.findBlacklistedIds(
                appointments.stream().map(Appointment::getVisitorId).filter(Objects::nonNull).distinct().toList());
        for (Appointment appointment : appointments) {
            String plate = VehicleAccessService.normalize(appointment.getPlateNo());
            if (plate.isEmpty() || byPlate.containsKey(plate)) {
                continue;
            }
            // 访客被拉黑：即使预约单还在有效期，也不能继续下发（已下发的下一轮会被删掉）
            if (appointment.getVisitorId() != null && blacklistedVisitors.contains(appointment.getVisitorId())) {
                continue;
            }
            List<Long> gates = gatesByAppointment.getOrDefault(appointment.getId(), List.of());
            if (gateId != null && !gates.contains(gateId)) {
                continue;
            }
            String name = visitorNames.getOrDefault(appointment.getVisitorId(), "");
            byPlate.put(plate, new PlatePayload(plate, PlatePayload.OWNER_VISITOR, appointment.getId(),
                    name, null, appointment.getStartTime(), appointment.getEndTime()));
        }
        return new ArrayList<>(byPlate.values());
    }

    /**
     * 把车牌清单推给车闸相机（全量覆盖语义，由适配器决定具体报文）。
     *
     * <p>厂商协议未接入时该设备记为 {@code PENDING_VENDOR}，其余设备照常下发。
     */
    public SyncResult sync(Long gateId) {
        List<PlatePayload> plates = snapshot(gateId);
        // 云车场（docs/19）：先对账下发，再按设备回显结果；未启用时只算差异（dry-run）
        VehiclePlateCloudService.SyncResult cloud = cloudService.reconcile(plates);
        List<Device> devices = vehicleDeviceService.list(gateId);
        if (devices.isEmpty()) {
            return new SyncResult(plates.size(), 0, List.of(), cloud);
        }
        List<DeviceResult> results = new ArrayList<>();
        int pending = 0;
        for (Device device : devices) {
            if (cloudService.enabled()) {
                results.add(new DeviceResult(device.getId(), device.getDeviceSn(), device.getDeviceName(),
                        device.getGateId(), cloud.failed() > 0 ? "CLOUD_PARTIAL" : "CLOUD_PUSHED",
                        "已通过车场开放接口下发（新增 " + cloud.added() + " / 修改 " + cloud.modified()
                                + " / 删除 " + cloud.removed() + " / 失败 " + cloud.failed() + "）",
                        plates.size()));
                continue;
            }
            if (!capabilityService.supports(device, DeviceCapability.PLATE_UPSERT)) {
                // 车闸相机走 MQTT 通道（协议无「读名单」能力，但增/删是支持的）
                com.fangke.visitor.vehicle.mqtt.VehicleMqttService mqtt = mqttServiceProvider.getIfAvailable();
                if (device.getChannel() == Device.Channel.VEHICLE && mqtt != null && mqtt.ready()) {
                    try {
                        Map<String, Object> outcome = mqtt.syncDevicePlates(device.getDeviceSn(), device.getGateId());
                        results.add(new DeviceResult(device.getId(), device.getDeviceSn(), device.getDeviceName(),
                                device.getGateId(), "PUSHED",
                                "MQTT 已下发（新增 " + outcome.get("added") + " / 更新 " + outcome.get("updated")
                                        + " / 删除 " + outcome.get("removed") + " / 未变 " + outcome.get("unchanged") + "）",
                                plates.size()));
                    } catch (Exception e) {
                        results.add(new DeviceResult(device.getId(), device.getDeviceSn(), device.getDeviceName(),
                                device.getGateId(), "FAILED", "MQTT 下发失败：" + e.getMessage(), plates.size()));
                        log.warn("车牌 MQTT 下发失败 device={}：{}", device.getDeviceSn(), e.getMessage());
                    }
                    continue;
                }
                pending++;
                results.add(new DeviceResult(device.getId(), device.getDeviceSn(), device.getDeviceName(),
                        device.getGateId(), "PENDING_VENDOR",
                        "厂商协议未接入：平台已备好 " + plates.size() + " 条车牌，等车牌下发接口文档",
                        plates.size()));
                continue;
            }
            try {
                DeviceAdapter adapter = adapterFactory.resolve(device);
                adapter.pushPlates(device, plates);
                results.add(new DeviceResult(device.getId(), device.getDeviceSn(), device.getDeviceName(),
                        device.getGateId(), "PUSHED", "已下发", plates.size()));
            } catch (DeviceException e) {
                results.add(new DeviceResult(device.getId(), device.getDeviceSn(), device.getDeviceName(),
                        device.getGateId(), "FAILED", e.getMessage(), plates.size()));
                log.warn("车牌下发失败 device={}：{}", device.getDeviceSn(), e.getMessage());
            }
        }
        log.info("车牌名单下发 gate={} 车牌={} 台数={} 待接入={}", gateId, plates.size(), devices.size(), pending);
        return new SyncResult(plates.size(), pending, results, cloud);
    }

    /** 清单按来源统计（管理端展示用）。 */
    public Map<String, Integer> countByOwner(List<PlatePayload> plates) {
        Set<String> employees = new LinkedHashSet<>();
        Set<String> visitors = new LinkedHashSet<>();
        for (PlatePayload plate : plates) {
            if (PlatePayload.OWNER_EMPLOYEE.equals(plate.ownerType())) {
                employees.add(plate.plateNo());
            } else {
                visitors.add(plate.plateNo());
            }
        }
        Map<String, Integer> view = new LinkedHashMap<>();
        view.put("employee", employees.size());
        view.put("visitor", visitors.size());
        view.put("total", plates.size());
        return view;
    }

    /** 车牌有效期结束时间展示用（null = 长期）。 */
    public String validToText(LocalDateTime validTo) {
        return validTo == null ? "长期" : validTo.toLocalDate().toString();
    }
}
