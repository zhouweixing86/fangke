package com.fangke.visitor.approval.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.AppointmentGateService;
import com.fangke.visitor.appointment.service.ApprovalStarter;
import com.fangke.visitor.approval.model.ApprovalConfig;
import com.fangke.visitor.approval.model.ApprovalTask;
import com.fangke.visitor.approval.repository.ApprovalActionRepository;
import com.fangke.visitor.approval.repository.ApprovalConfigRepository;
import com.fangke.visitor.approval.repository.ApprovalTaskRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.device.remote.DeviceQrConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 审批流（M5）：1/2 级审批、驳回、转办、加签、催办、待办角标。
 *
 * <p>核心规则：
 * <ul>
 *   <li>一级审批人 = 被访人；匹配不到时用配置的默认审批人（BR-A-002）</li>
 *   <li>二级审批人在一级通过时指定，候选仅 1 人时自动选中（C-24）</li>
 *   <li>二级审批人与一级相同 → 系统自动跳过（status=4）</li>
 *   <li>驳回必须填意见；驳回或撤回都要让其余待办失效</li>
 *   <li>加签 = 同层级并行任务，全部通过才进入下一级</li>
 *   <li>催办：同一任务按配置间隔与次数上限限流（BR-N-007）</li>
 * </ul>
 */
@Service
public class ApprovalService implements ApprovalStarter, ReminderPort {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int ACTION_PASS = 1;
    private static final int ACTION_REJECT = 2;
    private static final int ACTION_TRANSFER = 3;
    private static final int ACTION_COUNTERSIGN = 4;
    private static final int ACTION_REMIND = 5;
    private static final int ACTION_SKIP = 6;

    public record TodoView(long taskId, int level, String appointmentNo, String visitorName,
                           String idCardMask, String purpose, String startTime, String endTime) {
    }

    public record ActionView(int action, String actionText, String operatorId, int operatorType,
                             String comment, List<String> gates, String at) {
    }

    /** 审批页门点选择：可选门点 + 访客申请集合 + 当前批准集合（默认等于申请集合）。 */
    public record GateOption(Long id, String name) {
    }

    public record GateAssignment(int gateScope, List<GateOption> selectable,
                                 List<Long> appliedGateIds, List<Long> approvedGateIds) {
    }

    public record DetailView(long taskId, int level, int status, String statusText,
                             String appointmentNo, int appointmentStatus, String appointmentStatusText,
                             String visitorName, String idCardMask, String visitorType, String purpose,
                             String visitPhoneMask, String startTime, String endTime,
                             GateAssignment gates,
                             boolean canAct, List<ActionView> actions,
                             /** 访客是否申请二维码开门（审批页据 this 决定要不要显示开关） */
                             boolean qrRequested,
                             /** 当前是否已同意二维码开门 */
                             boolean qrApproved) {
    }

    public record PassResult(String appointmentNo, int status, String statusText,
                             boolean needSecondLevel, boolean dispatchStarted) {
    }

    private final ApprovalConfigRepository configRepository;
    private final ApprovalTaskRepository taskRepository;
    private final ApprovalActionRepository actionRepository;
    private final AppointmentRepository appointmentRepository;
    private final VisitorRepository visitorRepository;
    private final NotifyPort notifyPort;
    private final DispatchTrigger dispatchTrigger;
    private final AppointmentGateService appointmentGateService;
    private final DeviceQrConfigService qrConfigService;

    public ApprovalService(ApprovalConfigRepository configRepository,
                           ApprovalTaskRepository taskRepository,
                           ApprovalActionRepository actionRepository,
                           AppointmentRepository appointmentRepository,
                           VisitorRepository visitorRepository,
                           NotifyPort notifyPort,
                           DispatchTrigger dispatchTrigger,
                           AppointmentGateService appointmentGateService,
                           DeviceQrConfigService qrConfigService) {
        this.configRepository = configRepository;
        this.taskRepository = taskRepository;
        this.actionRepository = actionRepository;
        this.appointmentRepository = appointmentRepository;
        this.visitorRepository = visitorRepository;
        this.notifyPort = notifyPort;
        this.dispatchTrigger = dispatchTrigger;
        this.appointmentGateService = appointmentGateService;
        this.qrConfigService = qrConfigService;
    }

    /* ---------------- 启动 / 取消 ---------------- */

    /**
     * 创建审核任务：**单级**，指派给管理端角色（默认 GUARD，即登录管理端的保安/管理员）。
     *
     * <p>2026-11-21 客户确认：员工不再登录审批，预约统一由管理端审核。
     * 被访人（{@code visit_employee_id}）仅作为"拜访对象"记录，不再参与审批。
     */
    @Override
    @Transactional
    public void start(Appointment appointment) {
        ApprovalConfig config = configRepository.loadGlobal();
        String role = config.roleOrDefault();
        long taskId = taskRepository.insert(ApprovalTask.roleTask(appointment.getId(), 1, role));

        appointment.setStatus(Appointment.PENDING_L1);
        appointment.setCurrentTaskId(taskId);
        appointmentRepository.update(appointment);

        log.info("创建管理端审核任务 appointment={} task={} role={}",
                appointment.getAppointmentNo(), taskId, role);
    }

    @Override
    @Transactional
    public void cancel(Appointment appointment) {
        taskRepository.invalidatePending(appointment.getId());
        log.info("预约撤回，已失效待办 appointment={}", appointment.getAppointmentNo());
    }

    /* ---------------- 审批动作 ---------------- */

    /**
     * 管理端审核通过（单级审批）。操作人是后台账号（sys_user），不是员工。
     */
    @Transactional
    public PassResult guardPass(Long taskId, Long operatorUserId, List<String> roles, String comment,
                                List<Long> approvedGateIds) {
        return guardPass(taskId, operatorUserId, roles, comment, approvedGateIds, null);
    }

    /**
     * 管理端审核通过（单级审批）。
     *
     * @param qrApproved 是否同意二维码开门；{@code null} 表示不改
     */
    @Transactional
    public PassResult guardPass(Long taskId, Long operatorUserId, List<String> roles, String comment,
                                List<Long> approvedGateIds, Boolean qrApproved) {
        ApprovalTask task = requirePendingRole(taskId, roles);
        Appointment appointment = loadAppointment(task.appointmentId());
        applyQrDecision(appointment, qrApproved, operatorUserId);

        List<Long> approved = approvedGateIds == null
                ? null : appointmentGateService.saveApproved(appointment, approvedGateIds);
        actionRepository.insert(taskId, appointment.getId(), operatorUserId, ApprovalTask.TYPE_ROLE,
                ACTION_PASS, null, trim(comment), gateSnapshot(approved));
        taskRepository.updateStatus(taskId, ApprovalTask.STATUS_PASSED);

        if (taskRepository.findPendingByAppointmentAndLevel(appointment.getId(), task.level()).isEmpty()) {
            return finalizeApprove(appointment, false);
        }
        log.info("还有同级复核未完成 appointment={} level={}",
                appointment.getAppointmentNo(), task.level());
        return new PassResult(appointment.getAppointmentNo(), appointment.getStatus(),
                Appointment.statusText(appointment.getStatus()), false, false);
    }

    /** 二级驳回（门卫拒绝放行）：整单驳回并记录原因。 */
    @Transactional
    public void guardReject(Long taskId, Long operatorUserId, List<String> roles, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "拒绝放行必须填写原因");
        }
        ApprovalTask task = requirePendingRole(taskId, roles);
        Appointment appointment = loadAppointment(task.appointmentId());

        actionRepository.insert(taskId, appointment.getId(), operatorUserId, ApprovalTask.TYPE_ROLE,
                ACTION_REJECT, null, comment.trim(), null);
        taskRepository.updateStatus(taskId, ApprovalTask.STATUS_REJECTED);
        taskRepository.invalidatePending(appointment.getId());

        appointment.setStatus(Appointment.REJECTED);
        appointment.setRejectReason(comment.trim());
        appointment.setCurrentTaskId(null);
        appointmentRepository.update(appointment);

        notifyPort.approvalResult(appointment.getId(), false, comment.trim());
        log.info("门卫拒绝放行 appointment={} task={} operator={} 原因={}",
                appointment.getAppointmentNo(), taskId, operatorUserId, comment.trim());
    }

    /** 催办：同一任务按配置间隔与次数上限限流。 */
    @Transactional
    public int remind(Long taskId, Long operatorId) {
        ApprovalTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "审批任务不存在：" + taskId));
        if (!task.pending()) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_HANDLED, "该任务已处理，无需催办");
        }
        ApprovalConfig config = configRepository.loadGlobal();
        if (task.reminderCount() >= config.reminderMaxPerTask()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "该任务最多催办 " + config.reminderMaxPerTask() + " 次");
        }
        if (task.lastReminderAt() != null) {
            long minutes = Duration.between(task.lastReminderAt(), LocalDateTime.now()).toMinutes();
            if (minutes < config.reminderIntervalMin()) {
                throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                        "催办过于频繁，请 " + (config.reminderIntervalMin() - minutes) + " 分钟后再试");
            }
        }
        taskRepository.markReminder(taskId);
        actionRepository.insert(taskId, task.appointmentId(), operatorId, ACTION_REMIND, null, null);
        notifyPort.reminder(task.assigneeId(), taskId);
        int remaining = config.reminderMaxPerTask() - task.reminderCount() - 1;
        log.info("催办 task={} operator={} 剩余次数={}", taskId, operatorId, remaining);
        return remaining;
    }

    /** 按预约单催办：找到当前待处理任务后走同一套限流逻辑。 */
    @Override
    @Transactional
    public int remindByAppointment(Long appointmentId, Long operatorId) {
        ApprovalTask task = taskRepository.findPendingByAppointment(appointmentId).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                        "该预约单当前没有待处理的审批任务"));
        return remind(task.id(), operatorId);
    }

    /* ---------------- 保安复核（原二级，现为唯一审核入口） ---------------- */

    public long guardTodoCount(List<String> roles) {
        String role = guardRole(roles);
        return role == null ? 0 : taskRepository.countPendingByRole(role);
    }

    public PageResult<TodoView> guardTodoList(List<String> roles, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String role = guardRole(roles);
        if (role == null) {
            return PageResult.of(0, safePage, safeSize, List.of());
        }
        List<ApprovalTaskRepository.TodoRow> rows = taskRepository.findTodoByRole(role,
                (safePage - 1) * safeSize, safeSize);
        List<TodoView> list = rows.stream()
                .map(r -> new TodoView(r.taskId(), r.level(), r.appointmentNo(), r.visitorName(),
                        r.idCardMask(), r.purpose(),
                        DATETIME.format(r.startTime()), DATETIME.format(r.endTime())))
                .toList();
        return PageResult.of(taskRepository.countPendingByRole(role), safePage, safeSize, list);
    }

    public DetailView guardDetail(Long taskId, List<String> roles) {
        ApprovalTask task = requireExists(taskId);
        boolean canAct = task.pending() && task.roleTask()
                && (roles.contains("ADMIN") || roles.contains(task.assigneeRole()));
        return buildDetail(task, canAct);
    }

    private DetailView buildDetail(ApprovalTask task, boolean canAct) {
        Appointment appointment = loadAppointment(task.appointmentId());
        Visitor visitor = visitorRepository.findById(appointment.getVisitorId()).orElse(null);

        List<ActionView> actions = actionRepository.listByAppointment(appointment.getId()).stream()
                .map(a -> new ActionView(a.action(), actionText(a.action()),
                        String.valueOf(a.operatorId()), a.operatorType(), a.comment(),
                        gateNames(a.gateSnapshot()), DATETIME.format(a.createdAt())))
                .toList();

        List<GateOption> selectable = appointmentGateService.selectableGates().stream()
                .map(g -> new GateOption(g.id(), g.gateName()))
                .toList();
        GateAssignment gates = new GateAssignment(appointment.getGateScope(), selectable,
                appointmentGateService.appliedGateIds(appointment),
                appointmentGateService.approvedGateIds(appointment));
        return new DetailView(task.id(), task.level(), task.status(), taskStatusText(task.status()),
                appointment.getAppointmentNo(), appointment.getStatus(),
                Appointment.statusText(appointment.getStatus()),
                visitor == null ? "" : visitor.getName(),
                visitor == null ? "" : visitor.getIdCardMask(),
                appointment.getVisitorType(), appointment.getPurpose(), appointment.getVisitPhoneMask(),
                DATETIME.format(appointment.getStartTime()), DATETIME.format(appointment.getEndTime()),
                gates, canAct, actions,
                appointment.isQrRequested(), appointment.isQrApproved());
    }

    private ApprovalTask requireExists(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "审批任务不存在：" + taskId));
    }

    /** 把审批动作里存的门点快照还原成门点名，供审批轨迹展示。 */
    private List<String> gateNames(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return List.of();
        }
        List<Long> ids = Arrays.stream(snapshot.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
        return appointmentGateService.namesOf(ids);
    }

    public List<TodoView> todoForAppointment(Long appointmentId) {
        return taskRepository.findPendingByAppointment(appointmentId).stream()
                .map(t -> new TodoView(t.id(), t.level(),
                        appointmentRepository.findById(t.appointmentId())
                                .map(Appointment::getAppointmentNo).orElse(""),
                        "", "", "", "", ""))
                .toList();
    }

    public ApprovalConfig getConfig() {
        return configRepository.loadGlobal();
    }

    @Transactional
    public ApprovalConfig updateConfig(ApprovalConfig config) {
        // 单级审批：审核角色必填，级数固定 1（传 2 会被强制拉回，避免又出现"等员工审批"的死单）
        ApprovalConfig normalized = new ApprovalConfig(config.id(), config.scope(), 1,
                config.defaultL1User(), config.l2CandidateIds(), config.roleOrDefault(),
                config.reminderIntervalMin(), config.reminderMaxPerTask());
        configRepository.saveGlobal(normalized);
        log.info("审批配置更新 level=1 审核角色={} 催办间隔={}分 上限={}次",
                normalized.roleOrDefault(), normalized.reminderIntervalMin(),
                normalized.reminderMaxPerTask());
        return configRepository.loadGlobal();
    }

    /* ---------------- 内部 ---------------- */

    private PassResult finalizeApprove(Appointment appointment, boolean needSecond) {
        // 终审：确保批准集合已落库（申请「全部门点」的在这里快照成当时的启用门点）
        appointmentGateService.saveApproved(appointment, null);
        appointment.setStatus(Appointment.APPROVED);
        appointment.setCurrentTaskId(null);
        appointmentRepository.update(appointment);
        dispatchTrigger.trigger(appointment);
        notifyPort.approvalResult(appointment.getId(), true, null);
        log.info("终审通过 appointment={}，已触发授权下发", appointment.getAppointmentNo());
        return new PassResult(appointment.getAppointmentNo(), appointment.getStatus(),
                Appointment.statusText(appointment.getStatus()), needSecond, true);
    }

    /** 门点集合快照：写进审批动作流水，用于对比“申请 vs 批准”。 */
    /**
     * 处理「二维码开门」审批决定。
     *
     * <p>访客申请了才允许同意；不同意则显式置 0，便于访客端立刻看不到取码入口。
     */
    private void applyQrDecision(Appointment appointment, Boolean qrApproved, Long operatorId) {
        if (qrApproved == null) {
            return;
        }
        if (qrApproved && !appointment.isQrRequested()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "访客没有申请二维码开门，不能直接同意");
        }
        appointment.setQrApproved(qrApproved);
        appointment.setQrApprovedBy(qrApproved ? operatorId : null);
        appointment.setQrApprovedAt(qrApproved ? LocalDateTime.now() : null);
        appointmentRepository.update(appointment);
        log.info("二维码开门审批 appointment={} approved={} operator={}",
                appointment.getAppointmentNo(), qrApproved, operatorId);
        if (qrApproved) {
            // 同意二维码开门的同时把设备接到平台核验上，现场不用再手工配 thirdQrUrl
            try {
                qrConfigService.requestParamSync();
            } catch (Exception e) {
                log.warn("二维码核验地址下发失败 appointment={}：{}", appointment.getAppointmentNo(), e.getMessage());
            }
        }
    }

    private String gateSnapshot(List<Long> gateIds) {
        if (gateIds == null || gateIds.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : gateIds) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * 取当前账号可处理的二级复核角色：角色匹配或管理员都行（管理员兜底，避免没人能批）。
     *
     * @return null 表示该账号没有复核权限
     */
    private String guardRole(List<String> roles) {
        String role = configRepository.loadGlobal().roleOrDefault();
        if (roles.contains(role) || roles.contains("ADMIN")) {
            return role;
        }
        return null;
    }

    private ApprovalTask requirePendingRole(Long taskId, List<String> roles) {
        ApprovalTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "审批任务不存在：" + taskId));
        if (!task.pending()) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_HANDLED, "该任务已处理");
        }
        if (!task.roleTask()) {
            throw new BusinessException(ErrorCode.NOT_TASK_OWNER, "该任务不是保安复核任务");
        }
        if (roles.contains("ADMIN") || roles.contains(task.assigneeRole())) {
            return task;
        }
        throw new BusinessException(ErrorCode.NOT_TASK_OWNER, "不是该任务的审批人");
    }

    private ApprovalTask requirePendingOwned(Long taskId, Long operatorId) {
        ApprovalTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "审批任务不存在：" + taskId));
        if (!task.pending()) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_HANDLED, "该任务已被处理");
        }
        if (task.roleTask()) {
            // 二级是保安复核任务，必须走 /api/guard/approval/** 处理
            throw new BusinessException(ErrorCode.NOT_TASK_OWNER, "该任务需由保安在门卫工作台复核");
        }
        if (operatorId == null || !task.assigneeId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.NOT_TASK_OWNER, "您不是该任务的审批人");
        }
        return task;
    }

    private Appointment loadAppointment(Long appointmentId) {
        return appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "预约单不存在：" + appointmentId));
    }

    private String actionText(int action) {
        return switch (action) {
            case ACTION_PASS -> "通过";
            case ACTION_REJECT -> "驳回";
            case ACTION_TRANSFER -> "转办";
            case ACTION_COUNTERSIGN -> "加签";
            case ACTION_REMIND -> "催办";
            case ACTION_SKIP -> "系统跳过";
            default -> "未知";
        };
    }

    private String taskStatusText(int status) {
        return switch (status) {
            case ApprovalTask.STATUS_PENDING -> "待处理";
            case ApprovalTask.STATUS_PASSED -> "已通过";
            case ApprovalTask.STATUS_REJECTED -> "已驳回";
            case ApprovalTask.STATUS_SKIPPED -> "已跳过";
            case ApprovalTask.STATUS_TRANSFERRED -> "已转办";
            case ApprovalTask.STATUS_INVALID -> "已失效";
            default -> "未知";
        };
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** 供详情页展示同行人（M6 会用，先留公共方法）。 */
    public List<AppointmentMate> mates(Long appointmentId) {
        return appointmentRepository.findMates(appointmentId);
    }
}
