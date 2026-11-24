package com.fangke.visitor.approval.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.AppointmentGateService;
import com.fangke.visitor.appointment.service.ApprovalStarter;
import com.fangke.visitor.approval.model.ApprovalCc;
import com.fangke.visitor.approval.model.ApprovalConfig;
import com.fangke.visitor.approval.model.ApprovalNode;
import com.fangke.visitor.approval.model.ApprovalTask;
import com.fangke.visitor.approval.model.ApprovalTemplate;
import com.fangke.visitor.approval.repository.ApprovalActionRepository;
import com.fangke.visitor.approval.repository.ApprovalConfigRepository;
import com.fangke.visitor.approval.repository.ApprovalTaskRepository;
import com.fangke.visitor.approval.repository.ApprovalTemplateRepository;
import com.fangke.visitor.admin.repository.AdminUserRepository;
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
    private static final int ACTION_CC = 7;

    public record TodoView(long taskId, int level, String nodeName, String appointmentNo, String visitorName,
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

    public record DetailView(long taskId, int level, String nodeName, int status, String statusText,
                             String appointmentNo, int appointmentStatus, String appointmentStatusText,
                             String visitorName, String idCardMask, String visitorType, String purpose,
                             String visitPhoneMask, String startTime, String endTime,
                             GateAssignment gates,
                             boolean canAct, List<ActionView> actions,
                             /** 访客是否申请二维码开门（审批页据 this 决定要不要显示开关） */
                             boolean qrRequested,
                             /** 当前是否已同意二维码开门 */
                             boolean qrApproved,
                             /** 本节点是否允许调整门点（模板节点配置） */
                             boolean allowEditGates,
                             /** 本节点是否允许审批二维码 */
                             boolean allowQr) {
    }

    public record PassResult(String appointmentNo, int status, String statusText,
                             boolean needSecondLevel, boolean dispatchStarted) {
    }

    private final ApprovalConfigRepository configRepository;
    private final ApprovalTemplateRepository templateRepository;
    private final ApprovalTaskRepository taskRepository;
    private final ApprovalActionRepository actionRepository;
    private final AppointmentRepository appointmentRepository;
    private final VisitorRepository visitorRepository;
    private final AdminUserRepository adminUserRepository;
    private final NotifyPort notifyPort;
    private final CcPort ccPort;
    private final DispatchTrigger dispatchTrigger;
    private final AppointmentGateService appointmentGateService;
    private final DeviceQrConfigService qrConfigService;

    public ApprovalService(ApprovalConfigRepository configRepository,
                           ApprovalTemplateRepository templateRepository,
                           ApprovalTaskRepository taskRepository,
                           ApprovalActionRepository actionRepository,
                           AppointmentRepository appointmentRepository,
                           VisitorRepository visitorRepository,
                           AdminUserRepository adminUserRepository,
                           NotifyPort notifyPort,
                           CcPort ccPort,
                           DispatchTrigger dispatchTrigger,
                           AppointmentGateService appointmentGateService,
                           DeviceQrConfigService qrConfigService) {
        this.configRepository = configRepository;
        this.templateRepository = templateRepository;
        this.taskRepository = taskRepository;
        this.actionRepository = actionRepository;
        this.appointmentRepository = appointmentRepository;
        this.visitorRepository = visitorRepository;
        this.adminUserRepository = adminUserRepository;
        this.notifyPort = notifyPort;
        this.ccPort = ccPort;
        this.dispatchTrigger = dispatchTrigger;
        this.appointmentGateService = appointmentGateService;
        this.qrConfigService = qrConfigService;
    }

    /* ---------------- 启动 / 取消 ---------------- */

    /**
     * 创建审批任务：按**启用的全局模板**建第 1 个节点的任务。
     *
     * <p>没有模板（或模板没配节点）时退回单级角色审批（用 {@code approval_config.l2_role}），
     * 保证升级后即使没配流程也能正常走。
     */
    @Override
    @Transactional
    public void start(Appointment appointment) {
        ApprovalTemplate template = templateRepository.findEnabledGlobal().orElse(null);
        ApprovalNode first = template == null ? null : template.firstNode();
        if (template == null || first == null) {
            ApprovalConfig config = configRepository.loadGlobal();
            String role = config.roleOrDefault();
            long taskId = taskRepository.insert(ApprovalTask.roleTask(appointment.getId(), 1, role,
                    null, null, "管理端审核"));
            appointment.setStatus(Appointment.PENDING_L1);
            appointment.setCurrentTaskId(taskId);
            appointmentRepository.update(appointment);
            log.warn("未配置审批模板，退回单级审批 appointment={} role={}",
                    appointment.getAppointmentNo(), role);
            return;
        }

        long firstTaskId = createNodeTasks(appointment, template, first);
        appointment.setStatus(first.seq() <= 1 ? Appointment.PENDING_L1 : Appointment.PENDING_L2);
        appointment.setCurrentTaskId(firstTaskId);
        appointmentRepository.update(appointment);

        // 提交时抄送（只通知、不阻断）
        for (ApprovalCc cc : template.submitCcs()) {
            ccPort.ccToRole(cc.targetValue(), appointment.getId(), appointment.getAppointmentNo(),
                    visitorNameOf(appointment), timeRangeOf(appointment), "提交时");
        }
        log.info("创建审批任务 appointment={} 模板={} 节点1={} 模式={}",
                appointment.getAppointmentNo(), template.id(), first.nodeName(), first.modeText());
    }

    /** 按节点配置建任务；会签节点给该角色每个启用账号各建一条，返回第一条任务 id。 */
    private long createNodeTasks(Appointment appointment, ApprovalTemplate template, ApprovalNode node) {
        if (node.allMode()) {
            List<Long> userIds = adminUserRepository.findActiveUserIdsByRole(node.assigneeRole());
            if (!userIds.isEmpty()) {
                long first = 0L;
                for (Long userId : userIds) {
                    long id = taskRepository.insert(ApprovalTask.adminUserTask(appointment.getId(),
                            node.seq(), userId, node.assigneeRole(), template.id(), node.id(),
                            node.nodeName()));
                    if (first == 0L) {
                        first = id;
                    }
                }
                return first;
            }
            log.warn("会签节点 {} 下角色 {} 没有启用账号，降级为角色任务", node.nodeName(), node.assigneeRole());
        }
        return taskRepository.insert(ApprovalTask.roleTask(appointment.getId(), node.seq(),
                node.assigneeRole(), template.id(), node.id(), node.nodeName()));
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
     * 管理端审核通过（多级审批）。
     *
     * <p>本节点办结后：会签未齐 → 等待；有下一节点 → 建下一节点任务；最后一节点 → 终审下发。
     *
     * @param qrApproved 是否同意二维码开门；{@code null} 表示不改（节点不允许时强制不改）
     */
    @Transactional
    public PassResult guardPass(Long taskId, Long operatorUserId, List<String> roles, String comment,
                                List<Long> approvedGateIds, Boolean qrApproved) {
        ApprovalTask task = requirePendingAdminTask(taskId, operatorUserId, roles);
        Appointment appointment = loadAppointment(task.appointmentId());
        ApprovalNode node = nodeOf(task);

        // 节点可以限制"能否改门点 / 能否批二维码"，越权传值直接忽略，避免被前端绕过
        Boolean effectiveQr = node == null || node.allowQr() ? qrApproved : null;
        List<Long> effectiveGates = node == null || node.allowEditGates() ? approvedGateIds : null;
        applyQrDecision(appointment, effectiveQr, operatorUserId);

        List<Long> approved = effectiveGates == null
                ? null : appointmentGateService.saveApproved(appointment, effectiveGates);
        actionRepository.insert(taskId, appointment.getId(), operatorUserId, ApprovalTask.TYPE_ROLE,
                ACTION_PASS, null, trim(comment), gateSnapshot(approved));
        taskRepository.updateStatus(taskId, ApprovalTask.STATUS_PASSED);

        List<ApprovalTask> pending = taskRepository.findPendingByAppointment(appointment.getId());
        boolean nodeDone = pending.stream().noneMatch(t -> sameNode(t, task));
        if (!nodeDone) {
            log.info("会签节点还有待处理 appointment={} node={} 剩余={}",
                    appointment.getAppointmentNo(), task.nodeName(), pending.size());
            return new PassResult(appointment.getAppointmentNo(), appointment.getStatus(),
                    Appointment.statusText(appointment.getStatus()), true, false);
        }

        ApprovalTemplate template = templateOf(task);
        if (node != null && template != null) {
            for (ApprovalCc cc : template.ccsOfNode(node.id())) {
                ccPort.ccToRole(cc.targetValue(), appointment.getId(), appointment.getAppointmentNo(),
                        visitorNameOf(appointment), timeRangeOf(appointment),
                        "节点『" + node.nodeName() + "』已通过");
            }
        }

        ApprovalNode next = template == null ? null : template.nodeAfter(task.level());
        if (next == null) {
            return finalizeApprove(appointment, false);
        }
        long nextTaskId = createNodeTasks(appointment, template, next);
        appointment.setStatus(Appointment.PENDING_L2);
        appointment.setCurrentTaskId(nextTaskId);
        appointmentRepository.update(appointment);
        log.info("进入下一审批节点 appointment={} 节点={}({})",
                appointment.getAppointmentNo(), next.seq(), next.nodeName());
        return new PassResult(appointment.getAppointmentNo(), appointment.getStatus(),
                Appointment.statusText(appointment.getStatus()), true, false);
    }

    /** 驳回（任一节点）：整单驳回并记录原因，其余待办全部失效。 */
    @Transactional
    public void guardReject(Long taskId, Long operatorUserId, List<String> roles, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "拒绝放行必须填写原因");
        }
        ApprovalTask task = requirePendingAdminTask(taskId, operatorUserId, roles);
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
        log.info("审批驳回 appointment={} task={} node={} operator={} 原因={}",
                appointment.getAppointmentNo(), taskId, task.nodeName(), operatorUserId, comment.trim());
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

    /* ---------------- 管理端审批（多级） ---------------- */

    public long guardTodoCount(List<String> roles, Long adminUserId) {
        return taskRepository.countPendingByRoles(roles, adminUserId);
    }

    public PageResult<TodoView> guardTodoList(List<String> roles, Long adminUserId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<ApprovalTaskRepository.TodoRow> rows = taskRepository.findTodoByRoles(roles, adminUserId,
                (safePage - 1) * safeSize, safeSize);
        List<TodoView> list = rows.stream()
                .map(r -> new TodoView(r.taskId(), r.level(), r.nodeName(), r.appointmentNo(), r.visitorName(),
                        r.idCardMask(), r.purpose(),
                        DATETIME.format(r.startTime()), DATETIME.format(r.endTime())))
                .toList();
        return PageResult.of(taskRepository.countPendingByRoles(roles, adminUserId), safePage, safeSize, list);
    }

    public DetailView guardDetail(Long taskId, List<String> roles, Long adminUserId) {
        ApprovalTask task = requireExists(taskId);
        boolean canAct = task.pending() && canAct(task, roles, adminUserId);
        return buildDetail(task, canAct);
    }

    private boolean canAct(ApprovalTask task, List<String> roles, Long adminUserId) {
        if (task.adminUserTask()) {
            return adminUserId != null && adminUserId.equals(task.assigneeId());
        }
        if (!task.roleTask()) {
            return false;
        }
        List<String> safeRoles = roles == null ? List.of() : roles;
        return safeRoles.contains("ADMIN") || safeRoles.contains(task.assigneeRole());
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
        ApprovalNode node = nodeOf(task);
        String nodeName = task.nodeName() == null || task.nodeName().isBlank()
                ? ("第 " + task.level() + " 级审核") : task.nodeName();
        return new DetailView(task.id(), task.level(), nodeName, task.status(), taskStatusText(task.status()),
                appointment.getAppointmentNo(), appointment.getStatus(),
                Appointment.statusText(appointment.getStatus()),
                visitor == null ? "" : visitor.getName(),
                visitor == null ? "" : visitor.getIdCardMask(),
                appointment.getVisitorType(), appointment.getPurpose(), appointment.getVisitPhoneMask(),
                DATETIME.format(appointment.getStartTime()), DATETIME.format(appointment.getEndTime()),
                gates, canAct, actions,
                appointment.isQrRequested(), appointment.isQrApproved(),
                node == null || node.allowEditGates(), node == null || node.allowQr());
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
                .map(t -> new TodoView(t.id(), t.level(), t.nodeName() == null ? "" : t.nodeName(),
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

    /** 待处理的**后台审批任务**校验：角色任务按角色，会签任务按账号。 */
    private ApprovalTask requirePendingAdminTask(Long taskId, Long operatorUserId, List<String> roles) {
        ApprovalTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "审批任务不存在：" + taskId));
        if (!task.pending()) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_HANDLED, "该任务已处理");
        }
        if (!task.adminApprovalTask()) {
            throw new BusinessException(ErrorCode.NOT_TASK_OWNER, "该任务不是后台审批任务");
        }
        if (canAct(task, roles, operatorUserId)) {
            return task;
        }
        throw new BusinessException(ErrorCode.NOT_TASK_OWNER, "不是该任务的审批人");
    }

    /** 任务对应的模板节点（历史任务没有 node_id，返回 null 表示按默认口径放开）。 */
    private ApprovalNode nodeOf(ApprovalTask task) {
        return task.nodeId() == null ? null : templateRepository.findNode(task.nodeId()).orElse(null);
    }

    /** 任务所属模板：优先按 task.template_id，历史任务回落到当前启用模板。 */
    private ApprovalTemplate templateOf(ApprovalTask task) {
        if (task.templateId() != null) {
            ApprovalTemplate byId = templateRepository.findById(task.templateId()).orElse(null);
            if (byId != null) {
                return byId;
            }
        }
        return templateRepository.findEnabledGlobal().orElse(null);
    }

    /** 是否同一节点：优先比 node_id，历史任务按 level 比。 */
    private boolean sameNode(ApprovalTask a, ApprovalTask b) {
        if (a.nodeId() != null || b.nodeId() != null) {
            return a.nodeId() != null && a.nodeId().equals(b.nodeId());
        }
        return a.level() == b.level();
    }

    private String visitorNameOf(Appointment appointment) {
        return visitorRepository.findById(appointment.getVisitorId())
                .map(Visitor::getName).orElse("访客");
    }

    private String timeRangeOf(Appointment appointment) {
        return DATETIME.format(appointment.getStartTime()) + " ~ " + DATETIME.format(appointment.getEndTime());
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
            case ACTION_CC -> "抄送";
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
