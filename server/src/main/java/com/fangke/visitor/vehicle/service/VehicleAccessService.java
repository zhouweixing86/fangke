package com.fangke.visitor.vehicle.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentGateRepository;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.storage.StorageService;
import com.fangke.visitor.vehicle.model.VehicleEvent;
import com.fangke.visitor.vehicle.repository.VehicleEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 车辆通行判定（C-68）。
 *
 * <p>判定不另设「车牌授权表」，而是实时查既有授权：
 * <ul>
 *   <li><b>员工</b>：{@code employee.plate_no} + 在岗 + 有效期 → 全部启用门点可通行；</li>
 *   <li><b>访客</b>：{@code appointment.plate_no} + 已授权状态 + 当天 + **批准门点集合**命中。</li>
 * </ul>
 * 这样访客车辆与人员授权共用同一套（同一个预约单、同一批门点、同一天有效期）。
 */
@Service
public class VehicleAccessService {

    private static final Logger log = LoggerFactory.getLogger(VehicleAccessService.class);
    private static final DateTimeFormatter DAY_PATH = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 判定结果。 */
    public record Decision(boolean allowed, String reason, Integer matchedType,
                           Long matchedId, String matchedName) {

        public static Decision deny(String reason) {
            return new Decision(false, reason, null, null, null);
        }
    }

    private final EmployeeRepository employeeRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentGateRepository appointmentGateRepository;
    private final VisitorRepository visitorRepository;
    private final VehicleEventRepository eventRepository;
    private final StorageService storageService;

    public VehicleAccessService(EmployeeRepository employeeRepository,
                                AppointmentRepository appointmentRepository,
                                AppointmentGateRepository appointmentGateRepository,
                                VisitorRepository visitorRepository,
                                VehicleEventRepository eventRepository,
                                StorageService storageService) {
        this.employeeRepository = employeeRepository;
        this.appointmentRepository = appointmentRepository;
        this.appointmentGateRepository = appointmentGateRepository;
        this.visitorRepository = visitorRepository;
        this.eventRepository = eventRepository;
        this.storageService = storageService;
    }

    /** 车牌统一去空格转大写（与员工车牌入库规则一致）。 */
    public static String normalize(String plateNo) {
        if (plateNo == null) {
            return "";
        }
        return plateNo.replace(" ", "").replace("\t", "").trim().toUpperCase();
    }

    /**
     * 判定该车牌此刻能否在指定门点通行。
     *
     * @param gateId 门点；为空表示无法确定门点（拒绝，避免跨门误放）
     */
    public Decision judge(String plateNo, Long gateId, LocalDateTime at) {
        String plate = normalize(plateNo);
        if (plate.isEmpty()) {
            return Decision.deny("车牌为空");
        }
        if (gateId == null) {
            return Decision.deny("未配置门点，无法判定");
        }
        LocalDate today = at.toLocalDate();

        // 1) 员工：长期有效，全部启用门点
        Optional<Employee> employee = employeeRepository.findActiveByPlateNo(plate);
        if (employee.isPresent()) {
            Employee e = employee.get();
            if (!inValidRange(e.getValidFrom(), e.getValidTo(), today)) {
                return new Decision(false, "员工通行证已过期", VehicleEvent.TYPE_EMPLOYEE,
                        e.getId(), e.getName());
            }
            return new Decision(true, "员工车辆", VehicleEvent.TYPE_EMPLOYEE, e.getId(), e.getName());
        }

        // 2) 访客：当天已授权 + 批准门点命中
        List<Appointment> appointments = appointmentRepository.findByPlateNoOnDate(plate, today);
        for (Appointment appointment : appointments) {
            List<Long> gates = appointmentGateRepository.findGateIds(
                    appointment.getId(), AppointmentGateRepository.STAGE_APPROVED);
            if (gates.isEmpty() || !gates.contains(gateId)) {
                continue;
            }
            String name = visitorRepository.findById(appointment.getVisitorId())
                    .map(v -> v.getName()).orElse("");
            return new Decision(true, "访客车辆", VehicleEvent.TYPE_VISITOR,
                    appointment.getId(), name);
        }
        if (!appointments.isEmpty()) {
            return Decision.deny("该车牌今日已授权，但不包含当前门点");
        }
        return Decision.deny("未登记车辆（车牌未绑定员工，也没有今日访客预约）");
    }

    /**
     * 落库一条车辆通行记录（含可选抓拍图）。
     *
     * @param photoBase64 抓拍图 base64（可空）；存对象存储失败只记日志
     * @return 写入后的记录；重复事件返回 null（幂等）
     */
    @Transactional
    public VehicleEvent record(String eventUuid, String deviceSn, Long gateId, String plateNo,
                               int direction, LocalDateTime eventTime, Integer confidence,
                               Decision decision, String photoBase64, String rawJson) {
        String plate = normalize(plateNo);
        String photoKey = savePhoto(plate, eventUuid, photoBase64);
        VehicleEvent event = new VehicleEvent(null, eventUuid, deviceSn, gateId, plate, direction,
                eventTime, confidence,
                decision == null ? null : decision.matchedType(),
                decision == null ? null : decision.matchedId(),
                decision == null ? null : decision.matchedName(),
                decision != null && decision.allowed()
                        ? VehicleEvent.RESULT_PASS : VehicleEvent.RESULT_DENY,
                decision == null ? null : decision.reason(),
                photoKey, rawJson);
        boolean inserted = eventRepository.insertIfAbsent(event);
        if (!inserted) {
            log.debug("车辆事件重复，已忽略 uuid={}", eventUuid);
            return null;
        }
        log.info("车辆通行 plate={} gate={} dir={} result={} 匹配={} 原因={}",
                plate, gateId, direction, event.resultStatus(),
                VehicleEvent.typeText(event.matchedType()), event.failReason());
        return event;
    }

    /* ---------------- 内部 ---------------- */

    private boolean inValidRange(LocalDate from, LocalDate to, LocalDate today) {
        if (from != null && today.isBefore(from)) {
            return false;
        }
        return to == null || !today.isAfter(to);
    }

    private String savePhoto(String plate, String eventUuid, String photoBase64) {
        if (photoBase64 == null || photoBase64.isBlank() || !storageService.configured()) {
            return null;
        }
        try {
            int comma = photoBase64.indexOf(',');
            String pure = comma >= 0 ? photoBase64.substring(comma + 1) : photoBase64;
            byte[] data = Base64.getDecoder().decode(pure);
            String key = "vehicle-event/" + DAY_PATH.format(LocalDate.now()) + "/" + eventUuid + ".jpg";
            storageService.putObject(key, data, "image/jpeg");
            return key;
        } catch (Exception e) {
            log.warn("车辆抓拍图保存失败 plate={}：{}", plate, e.getMessage());
            return null;
        }
    }
}
