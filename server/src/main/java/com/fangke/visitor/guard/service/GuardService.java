package com.fangke.visitor.guard.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.approval.service.ReminderPort;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.AccessEvent;
import com.fangke.visitor.device.service.AccessEventRepository;
import com.fangke.visitor.device.service.GrantChecker;
import com.fangke.visitor.device.service.VisitRecordService;
import com.fangke.visitor.guard.repository.GuardQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 保安工作台（M11）：看板、在访、待审批只读、催办、人工开门、手工签离。
 *
 * <p>权限边界（C-58）：保安<b>没有审批权</b>，也不能代录、不能读卡；
 * 人工开门只允许针对「已授权」的访客（C-59），员工代开门属人工核验放行（BR-V-003）。
 */
@Service
public class GuardService {

    private static final Logger log = LoggerFactory.getLogger(GuardService.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 人工开门的通行事件识别类型（平台自定义，区别于设备的 0/2/3/5）。 */
    private static final int IDENTIFY_TYPE_MANUAL = 9;

    public record DashboardView(int onsite, int pendingApproval, int devicesOnline, int devicesTotal,
                                int todayCheckins, String deviceStatusText) {
    }

    public record OnsiteView(String appointmentNo, String visitorName, String idCardMask, String purpose,
                             String checkinAt, String endTime, boolean overdue) {
    }

    public record PendingView(String appointmentNo, String visitorName, String idCardMask, String purpose,
                              int source, String sourceText, String startTime, String endTime,
                              String createdAt) {
    }

    public record OpenDoorRequest(String appointmentNo, Long employeeId, Long gateId, String reason) {
    }

    private final GuardQueryRepository queryRepository;
    private final AppointmentRepository appointmentRepository;
    private final AccessEventRepository accessEventRepository;
    private final GrantChecker grantChecker;
    private final DoorOpener doorOpener;
    private final VisitRecordService visitRecordService;
    private final ReminderPort reminderPort;
    private final AppProperties props;

    public GuardService(GuardQueryRepository queryRepository,
                        AppointmentRepository appointmentRepository,
                        AccessEventRepository accessEventRepository,
                        GrantChecker grantChecker,
                        DoorOpener doorOpener,
                        VisitRecordService visitRecordService,
                        ReminderPort reminderPort,
                        AppProperties props) {
        this.queryRepository = queryRepository;
        this.appointmentRepository = appointmentRepository;
        this.accessEventRepository = accessEventRepository;
        this.grantChecker = grantChecker;
        this.doorOpener = doorOpener;
        this.visitRecordService = visitRecordService;
        this.reminderPort = reminderPort;
        this.props = props;
    }

    public DashboardView dashboard() {
        GuardQueryRepository.Counts c = queryRepository.counts();
        String deviceText = c.devicesTotal() == 0 ? "无设备"
                : (c.devicesOnline() == c.devicesTotal() ? "全部在线"
                : (c.devicesOnline() == 0 ? "全部离线" : c.devicesOnline() + "/" + c.devicesTotal() + " 在线"));
        return new DashboardView(c.onsite(), c.pendingApproval(), c.devicesOnline(), c.devicesTotal(),
                c.todayCheckins(), deviceText);
    }

    public PageResult<OnsiteView> onsite(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        LocalDateTime now = LocalDateTime.now();
        List<OnsiteView> list = queryRepository.onsite((safePage - 1) * safeSize, safeSize).stream()
                .map(r -> new OnsiteView(
                        r.appointmentNo(),
                        r.visitorName(),
                        r.idCardMask(),
                        r.purpose(),
                        r.checkinAt() == null ? "" : DATETIME.format(r.checkinAt()),
                        r.endTime() == null ? "" : DATETIME.format(r.endTime()),
                        r.endTime() != null && now.isAfter(r.endTime())))
                .toList();
        return PageResult.of(queryRepository.countOnsite(), safePage, safeSize, list);
    }

    public PageResult<PendingView> pending(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<PendingView> list = queryRepository.pending((safePage - 1) * safeSize, safeSize).stream()
                .map(r -> new PendingView(
                        r.appointmentNo(),
                        r.visitorName(),
                        r.idCardMask(),
                        r.purpose(),
                        r.source(),
                        sourceText(r.source()),
                        DATETIME.format(r.startTime()),
                        DATETIME.format(r.endTime()),
                        r.createdAt() == null ? "" : DATETIME.format(r.createdAt())))
                .toList();
        return PageResult.of(queryRepository.countPending(), safePage, safeSize, list);
    }

    /** 催办：走审批模块的同一套限流（30 分钟内最多 2 次）。 */
    public int remind(String appointmentNo, Long operatorId) {
        Appointment appointment = loadAppointment(appointmentNo);
        return reminderPort.remindByAppointment(appointment.getId(), operatorId);
    }

    /**
     * 人工开门。
     *
     * <p>两种场景：
     * <ul>
     *   <li>访客凭证不可用：预约单必须已授权（GRANTED / 已签到）且授权在有效期内；</li>
     *   <li>员工刷脸失败：由保安人工核验身份后放行，必须填写原因（BR-V-003）。</li>
     * </ul>
     */
    public int openDoor(OpenDoorRequest request, Long operatorId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请求不能为空");
        }
        Long gateId = request.gateId() == null ? props.getGate().getDefaultId() : request.gateId();
        boolean employeeMode = request.employeeId() != null;
        String personNo = null;

        if (!employeeMode) {
            if (request.appointmentNo() == null || request.appointmentNo().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_MISSING,
                        "访客开门需要 appointmentNo；员工开门需要 employeeId");
            }
            Appointment appointment = loadAppointment(request.appointmentNo());
            if (appointment.getStatus() != Appointment.GRANTED
                    && appointment.getStatus() != Appointment.CHECKED_IN) {
                throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                        "该访客未授权（当前：" + Appointment.statusText(appointment.getStatus()) + "），不能开门");
            }
            GrantChecker.State state = grantChecker.check(appointment.getAppointmentNo(), gateId, Instant.now());
            if (state != GrantChecker.State.OK) {
                throw new BusinessException(ErrorCode.NOT_IN_AUTHORIZED_WINDOW,
                        "授权校验未通过：" + state);
            }
        } else if (request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "员工代开门必须填写人工核验说明");
        }

        int opened = doorOpener.open(gateId);
        recordManualEvent(request, gateId, personNo, operatorId);
        log.info("人工开门 gate={} operator={} appointment={} employee={} opened={} reason={}",
                gateId, operatorId, request.appointmentNo(), request.employeeId(), opened, request.reason());
        return opened;
    }

    /** 手工签离（BR-V-010）。 */
    public void manualCheckout(String appointmentNo, Long operatorId) {
        Appointment appointment = loadAppointment(appointmentNo);
        boolean ok = visitRecordService.manualCheckout(appointment.getId(), operatorId);
        if (!ok) {
            throw new BusinessException(ErrorCode.VISITOR_NOT_IN_SITE, "该人员当前不在访，无法签离");
        }
    }

    /* ---------------- 内部 ---------------- */

    private void recordManualEvent(OpenDoorRequest request, Long gateId, String personNo, Long operatorId) {
        try {
            String uuid = "M" + System.currentTimeMillis() + "-" + (operatorId == null ? "0" : operatorId);
            String raw = "{\"type\":\"manual_open\",\"operator\":" + operatorId
                    + ",\"appointmentNo\":" + quote(request.appointmentNo())
                    + ",\"employeeId\":" + request.employeeId()
                    + ",\"reason\":" + quote(request.reason()) + "}";
            accessEventRepository.insertIfAbsent(new AccessEvent(
                    uuid, null, "MANUAL", gateId, 1, LocalDateTime.now(),
                    IDENTIFY_TYPE_MANUAL, personNo, null, null, 1, null, null, null,
                    false, 0, 3, raw));
        } catch (Exception e) {
            // 留痕失败不能影响开门
            log.warn("人工开门事件留痕失败：{}", e.getMessage());
        }
    }

    private String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Appointment loadAppointment(String appointmentNo) {
        return appointmentRepository.findByNo(appointmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "预约单不存在：" + appointmentNo));
    }

    private String sourceText(int source) {
        return switch (source) {
            case Appointment.SOURCE_SELF -> "访客自约";
            case Appointment.SOURCE_INVITE -> "员工代邀";
            case Appointment.SOURCE_ONSITE -> "现场自助登记";
            default -> "未知";
        };
    }
}
