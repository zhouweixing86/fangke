package com.fangke.visitor.vehicle.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentGateRepository;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.PlateNo;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
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

    /** 判定结果。{@code personNo} 用于把车辆记录与行人记录按人合并。 */
    public record Decision(boolean allowed, String reason, Integer matchedType,
                           Long matchedId, String matchedName, String personNo) {

        public static Decision deny(String reason) {
            return new Decision(false, reason, null, null, null, null);
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
        return PlateNo.normalize(plateNo);
    }

    /**
     * 判定该车牌此刻能否在指定门点通行（单人口径：只取第一个匹配人）。
     *
     * @param gateId 门点；为空表示无法确定门点（拒绝，避免跨门误放）
     */
    public Decision judge(String plateNo, Long gateId, LocalDateTime at) {
        return judgeGroup(plateNo, gateId, at).get(0);
    }

    /**
     * 判定该车牌此刻能在指定门点通行的**所有人**。
     *
     * <p>员工 = 1 人；访客 = 主访客 + 预约同行人：团队预约一辆车进门，车上每个人各记一条通行记录，
     * 否则「一辆车 8 个人」只有 1 条记录，统计会失真。同行人没有独立人员编号时用「预约号-序号」兜底，
     * 保证记录仍能按人分开统计。
     *
     * @param gateId 门点；为空表示无法确定门点（拒绝，避免跨门误放）
     */
    public List<Decision> judgeGroup(String plateNo, Long gateId, LocalDateTime at) {
        String plate = normalize(plateNo);
        if (plate.isEmpty()) {
            return List.of(Decision.deny("车牌为空"));
        }
        if (gateId == null) {
            return List.of(Decision.deny("未配置门点，无法判定"));
        }
        LocalDate today = at.toLocalDate();

        // 1) 员工：长期有效，全部启用门点
        Optional<Employee> employee = employeeRepository.findActiveByPlateNo(plate);
        if (employee.isPresent()) {
            Employee e = employee.get();
            if (!inValidRange(e.getValidFrom(), e.getValidTo(), today)) {
                return List.of(new Decision(false, "员工通行证已过期", VehicleEvent.TYPE_EMPLOYEE,
                        e.getId(), e.getName(), e.getPersonNo()));
            }
            return List.of(new Decision(true, "员工车辆", VehicleEvent.TYPE_EMPLOYEE, e.getId(), e.getName(),
                    e.getPersonNo()));
        }

        // 2) 访客：当天已授权 + 批准门点命中
        List<Appointment> appointments = appointmentRepository.findByPlateNoOnDate(plate, today);
        for (Appointment appointment : appointments) {
            List<Long> gates = appointmentGateRepository.findGateIds(
                    appointment.getId(), AppointmentGateRepository.STAGE_APPROVED);
            if (gates.isEmpty() || !gates.contains(gateId)) {
                continue;
            }
            Optional<Visitor> visitor = visitorRepository.findById(appointment.getVisitorId());
            if (visitor.isEmpty()) {
                return List.of(Decision.deny("访客档案不存在"));
            }
            Visitor v = visitor.get();
            // matched_id 记访客 ID（不是预约单 ID），与行人记录的人员口径一致
            List<Decision> group = new ArrayList<>();
            group.add(new Decision(true, "访客车辆", VehicleEvent.TYPE_VISITOR,
                    v.getId(), v.getName(), v.getPersonNo()));
            int seq = 0;
            for (AppointmentMate mate : appointmentRepository.findMates(appointment.getId())) {
                if (mate.main()) {
                    continue;
                }
                seq++;
                String personNo = mate.personNo() == null || mate.personNo().isBlank()
                        ? matePersonNo(appointment.getAppointmentNo(), seq)
                        : mate.personNo();
                group.add(new Decision(true, "访客同行人", VehicleEvent.TYPE_VISITOR,
                        v.getId(), mate.name(), personNo));
            }
            return group;
        }
        if (!appointments.isEmpty()) {
            return List.of(Decision.deny("该车牌今日已授权，但不包含当前门点"));
        }
        return List.of(Decision.deny("未登记车辆（车牌未绑定员工，也没有今日访客预约）"));
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
        return record(eventUuid, deviceSn, gateId, plateNo, direction, eventTime, confidence,
                decision, photoBase64, null, rawJson);
    }

    /**
     * 落库一条车辆通行记录（含可选抓拍图）。
     *
     * @param photoBase64 抓拍图 base64（可空）；存对象存储失败只记日志
     * @param photoUrl    相机直传的抓拍图外链（如厂商 OSS 地址，可空）
     * @return 写入后的记录；重复事件返回 null（幂等）
     */
    @Transactional
    public VehicleEvent record(String eventUuid, String deviceSn, Long gateId, String plateNo,
                               int direction, LocalDateTime eventTime, Integer confidence,
                               Decision decision, String photoBase64, String photoUrl, String rawJson) {
        List<VehicleEvent> saved = recordGroup(eventUuid, deviceSn, gateId, plateNo, direction, eventTime,
                confidence, Collections.singletonList(decision), photoBase64, photoUrl, rawJson);
        return saved.isEmpty() ? null : saved.get(0);
    }

    /**
     * 一辆车带来的所有人各落一条记录（主访客 + 同行人）。
     *
     * <p>共用同一张抓拍图（只上传一次，其余记录引用同一个 key 与原始报文），
     * 事件号按 {@code 抓拍事件号-序号} 派生，相机重复补传时仍然幂等。
     *
     * @return 实际新增的记录（重复事件会被跳过）
     */
    @Transactional
    public List<VehicleEvent> recordGroup(String eventUuid, String deviceSn, Long gateId, String plateNo,
                                          int direction, LocalDateTime eventTime, Integer confidence,
                                          List<Decision> decisions, String photoBase64, String photoUrl,
                                          String rawJson) {
        String plate = normalize(plateNo);
        List<Decision> group = decisions == null || decisions.isEmpty()
                ? Collections.singletonList(null) : decisions;
        String photoKey = savePhoto(plate, eventUuid, photoBase64);
        List<VehicleEvent> saved = new ArrayList<>(group.size());
        for (int i = 0; i < group.size(); i++) {
            Decision decision = group.get(i);
            boolean first = i == 0;
            VehicleEvent event = new VehicleEvent(null, first ? eventUuid : eventUuid + "-" + i,
                    deviceSn, gateId, plate, direction, eventTime, confidence,
                    decision == null ? null : decision.matchedType(),
                    decision == null ? null : decision.matchedId(),
                    decision == null ? null : decision.matchedName(),
                    decision == null ? null : decision.personNo(),
                    decision != null && decision.allowed()
                            ? VehicleEvent.RESULT_PASS : VehicleEvent.RESULT_DENY,
                    decision == null ? null : decision.reason(),
                    photoKey, first ? photoUrl : null, first ? rawJson : null);
            if (!eventRepository.insertIfAbsent(event)) {
                log.debug("车辆事件重复，已忽略 uuid={}", event.eventUuid());
                continue;
            }
            saved.add(event);
            log.info("车辆通行 plate={} gate={} dir={} result={} 匹配={} 原因={}",
                    plate, gateId, direction, event.resultStatus(),
                    VehicleEvent.typeText(event.matchedType()), event.failReason());
        }
        return saved;
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 同行人兜底人员编号：{@code 预约号-序号}（如 V202611250001-1），
     * 保证团队预约里每个人都有自己的通行记录，而不是全挂在主访客名下。
     */
    static String matePersonNo(String appointmentNo, int seq) {
        String suffix = "-" + seq;
        String base = appointmentNo == null || appointmentNo.isBlank() ? "MATE" : appointmentNo.trim();
        int max = 20 - suffix.length();
        if (base.length() > max) {
            base = base.substring(0, max);
        }
        return base + suffix;
    }

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
