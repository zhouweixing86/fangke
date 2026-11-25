package com.fangke.visitor.approval.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.InMemoryAppointmentGateRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.AppointmentGateService;
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
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.remote.DeviceQrConfigService;
import com.fangke.visitor.gate.repository.InMemoryGateRepository;
import com.fangke.visitor.gate.service.GateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalServiceTest {

    /* ---------------- 测试替身 ---------------- */

    static class FakeConfigRepo implements ApprovalConfigRepository {
        ApprovalConfig config = new ApprovalConfig(1L, "GLOBAL", 1, null, List.of(), "GUARD", 30, 2);

        @Override
        public ApprovalConfig loadGlobal() {
            return config;
        }

        @Override
        public void saveGlobal(ApprovalConfig c) {
            config = c;
        }
    }

    /** 模板替身：默认没有模板（走兜底单级），用例里按需赋值。 */
    static class FakeTemplateRepo implements ApprovalTemplateRepository {
        ApprovalTemplate template;

        @Override
        public List<ApprovalTemplate> list() {
            return template == null ? List.of() : List.of(template);
        }

        @Override
        public Optional<ApprovalTemplate> findById(Long id) {
            return template != null && template.id().equals(id) ? Optional.of(template) : Optional.empty();
        }

        @Override
        public Optional<ApprovalTemplate> findEnabledGlobal() {
            return template != null && template.enabled() ? Optional.of(template) : Optional.empty();
        }

        @Override
        public Optional<ApprovalNode> findNode(Long nodeId) {
            if (template == null || nodeId == null) {
                return Optional.empty();
            }
            return template.nodes().stream().filter(n -> nodeId.equals(n.id())).findFirst();
        }

        @Override
        public long create(String templateName, String scope, boolean enabled, String remark) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateMeta(Long id, String templateName, boolean enabled, String remark) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceNodes(Long templateId, List<ApprovalNode> nodes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long nodeIdOf(Long templateId, int seq) {
            return template == null ? null : template.nodes().stream()
                    .filter(n -> n.seq() == seq).map(ApprovalNode::id).findFirst().orElse(null);
        }

        @Override
        public void replaceCcs(Long templateId, List<ApprovalCc> ccs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Long id) {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeTaskRepo implements ApprovalTaskRepository {        final Map<Long, ApprovalTask> store = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong();

        @Override
        public long insert(ApprovalTask t) {
            long id = seq.incrementAndGet();
            store.put(id, new ApprovalTask(id, t.appointmentId(), t.templateId(), t.nodeId(), t.nodeName(),
                    t.level(), t.assigneeId(), t.status(), 0, null, null, LocalDateTime.now(),
                    t.assigneeType(), t.assigneeRole()));
            return id;
        }

        @Override
        public Optional<ApprovalTask> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<ApprovalTask> findPendingByAppointment(Long appointmentId) {
            return store.values().stream()
                    .filter(t -> t.appointmentId().equals(appointmentId) && t.status() == 1).toList();
        }

        @Override
        public Optional<ApprovalTask> findPendingByAppointmentAndLevel(Long appointmentId, int level) {
            return store.values().stream()
                    .filter(t -> t.appointmentId().equals(appointmentId) && t.level() == level && t.status() == 1)
                    .findFirst();
        }

        @Override
        public long countPending(Long assigneeId) {
            return store.values().stream()
                    .filter(t -> t.assigneeId().equals(assigneeId) && t.status() == 1 && !t.roleTask())
                    .count();
        }

        @Override
        public long countPendingByRoles(List<String> roles, Long adminUserId) {
            return store.values().stream()
                    .filter(t -> t.status() == 1)
                    .filter(t -> t.adminUserTask()
                            ? t.assigneeId().equals(adminUserId)
                            : roles != null && roles.contains(t.assigneeRole()))
                    .count();
        }

        @Override
        public List<TodoRow> findTodo(Long assigneeId, int offset, int limit) {
            return List.of();
        }

        @Override
        public List<TodoRow> findTodoByRoles(List<String> roles, Long adminUserId, int offset, int limit) {
            return List.of();
        }

        @Override
        public void updateStatus(Long id, int status) {
            ApprovalTask t = store.get(id);
            store.put(id, t.withStatus(status));
        }

        @Override
        public void updateAssignee(Long id, Long assigneeId) {
            store.put(id, store.get(id).withAssignee(assigneeId));
        }

        @Override
        public void markReminder(Long id) {
            ApprovalTask t = store.get(id);
            store.put(id, new ApprovalTask(t.id(), t.appointmentId(), t.templateId(), t.nodeId(), t.nodeName(),
                    t.level(), t.assigneeId(), t.status(), t.reminderCount() + 1, LocalDateTime.now(),
                    t.finishedAt(), t.createdAt(), t.assigneeType(), t.assigneeRole()));
        }

        @Override
        public void invalidatePending(Long appointmentId) {
            store.replaceAll((id, t) -> t.appointmentId().equals(appointmentId) && t.status() == 1
                    ? t.withStatus(6) : t);
        }

        @Override
        public java.util.List<Long> findStalePending(java.time.LocalDateTime createdBefore,
                                                     java.time.LocalDateTime lastRemindBefore,
                                                     int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<DoneRow> findDoneByOperator(long operatorId, int operatorType, Integer action,
                                                          java.time.LocalDate from, java.time.LocalDate to,
                                                          int offset, int limit) {
            return java.util.List.of();
        }

        @Override
        public long countDoneByOperator(long operatorId, int operatorType, Integer action,
                                        java.time.LocalDate from, java.time.LocalDate to) {
            return 0;
        }

        @Override
        public DoneStats doneStats(long operatorId, int operatorType, java.time.LocalDate from,
                                   java.time.LocalDate to) {
            return new DoneStats(0, 0, 0);
        }
    }

    static class FakeActionRepo implements ApprovalActionRepository {
        final List<Long> actions = new ArrayList<>();

        @Override
        public void insert(Long taskId, Long appointmentId, Long operatorId, int action,
                           Long targetUser, String comment) {
            actions.add((long) action);
        }

        @Override
        public void insert(Long taskId, Long appointmentId, Long operatorId, int operatorType, int action,
                           Long targetUser, String comment, String gateSnapshot) {
            actions.add((long) action);
        }

        @Override
        public void insert(Long taskId, Long appointmentId, Long operatorId, String operatorName,
                           int operatorType, int action, Long targetUser, String comment,
                           String gateSnapshot) {
            actions.add((long) action);
        }

        @Override
        public List<ActionRow> listByAppointment(Long appointmentId) {
            return List.of();
        }
    }

    static class FakeAppointmentRepo implements AppointmentRepository {
        final Map<Long, Appointment> store = new LinkedHashMap<>();

        @Override
        public long insert(Appointment a) {
            long id = store.size() + 1;
            a.setId(id);
            store.put(id, a);
            return id;
        }

        @Override
        public void update(Appointment a) {
            store.put(a.getId(), a);
        }

        @Override
        public void updateStatus(Long id, int status) {
            store.get(id).setStatus(status);
        }

        @Override
        public Optional<Appointment> findByNo(String appointmentNo) {
            return store.values().stream().filter(a -> a.getAppointmentNo().equals(appointmentNo)).findFirst();
        }

        @Override
        public Optional<Appointment> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Appointment> findByVisitor(Long visitorId, Integer status, int offset, int limit) {
            return List.of();
        }

        @Override
        public long countByVisitor(Long visitorId, Integer status) {
            return 0;
        }

        @Override
        public Optional<Appointment> findInProgress(Long visitorId) {
            return Optional.empty();
        }

        @Override
        public void insertMate(AppointmentMate mate) {
        }

        @Override
        public List<AppointmentMate> findMates(Long appointmentId) {
            return List.of();
        }

        @Override
        public Optional<String> findMaxNoByPrefix(String prefix) {
            return Optional.empty();
        }

        @Override
        public java.util.List<Long> findExpirableIds(java.time.LocalDateTime before, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.fangke.visitor.appointment.model.Appointment> findActiveByVisitor(Long visitorId) {
            return java.util.List.of();
        }
    }

    static class FakeVisitorRepo implements VisitorRepository {
        final Map<Long, Visitor> store = new LinkedHashMap<>();

        @Override
        public Optional<Visitor> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Visitor> findByIdCardHmac(String idCardHmac) {
            return Optional.empty();
        }

        @Override
        public Optional<Visitor> findByPhoneHmac(String phoneHmac) {
            return Optional.empty();
        }

        @Override
        public long insert(Visitor visitor) {
            long id = store.size() + 1;
            visitor.setId(id);
            store.put(id, visitor);
            return id;
        }

        @Override
        public void update(Visitor visitor) {
            store.put(visitor.getId(), visitor);
        }

        @Override
        public Optional<Visitor> findByWxOpenid(String wxOpenid) {
            return Optional.empty();
        }

        @Override
        public void bindWxOpenid(Long visitorId, String wxOpenid) {
            // 单测不校验 openid 绑定
        }

        @Override
        public void updateFace(Long visitorId, String ossKey, int status) {
        }
        @Override
        public void recordConsent(Long visitorId, int consentType, String version, String ip, String ua) {
        }

        @Override
        public Optional<String> findMaxPersonNoByPrefix(String prefix) {
            return Optional.empty();
        }
    }

    /* ---------------- 固定数据 ---------------- */

    static class FakeFlowRepo implements com.fangke.visitor.approval.repository.AppointmentFlowRepository {
        final Map<Long, java.util.List<com.fangke.visitor.approval.model.FlowNode>> store = new LinkedHashMap<>();

        @Override
        public void saveAll(Long appointmentId, java.util.List<com.fangke.visitor.approval.model.FlowNode> nodes) {
            store.put(appointmentId, new java.util.ArrayList<>(nodes));
        }

        @Override
        public java.util.List<com.fangke.visitor.approval.model.FlowNode> findByAppointment(Long appointmentId) {
            return store.getOrDefault(appointmentId, java.util.List.of());
        }

        @Override
        public java.util.Optional<com.fangke.visitor.approval.model.FlowNode> find(Long appointmentId, int seq) {
            return findByAppointment(appointmentId).stream()
                    .filter(n -> n.seq() == seq).findFirst();
        }

        @Override
        public java.util.Optional<com.fangke.visitor.approval.model.FlowNode> next(Long appointmentId, int seq) {
            return findByAppointment(appointmentId).stream()
                    .filter(n -> n.seq() > seq).findFirst();
        }
    }

    private FakeConfigRepo configRepo;
    private FakeTemplateRepo templateRepo;
    private FakeTaskRepo taskRepo;
    private FakeActionRepo actionRepo;
    private FakeAppointmentRepo appointmentRepo;
    private FakeFlowRepo flowRepo;
    private AdminUserRepository adminUserRepo;
    private final List<String> notifications = new ArrayList<>();
    private final List<String> dispatches = new ArrayList<>();
    private final List<String> ccs = new ArrayList<>();
    private ApprovalService service;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        configRepo = new FakeConfigRepo();
        templateRepo = new FakeTemplateRepo();
        taskRepo = new FakeTaskRepo();
        actionRepo = new FakeActionRepo();
        appointmentRepo = new FakeAppointmentRepo();
        flowRepo = new FakeFlowRepo();
        adminUserRepo = org.mockito.Mockito.mock(AdminUserRepository.class);
        org.mockito.Mockito.when(adminUserRepo.findActiveUserIdsByRole("GUARD"))
                .thenReturn(List.of(101L, 102L));
        CcPort ccPort = (role, appointmentId, appointmentNo, visitorName, timeRange, stage) ->
                ccs.add(role + "@" + stage);

        NotifyPort notify = new NotifyPort() {
            @Override
            public void approvalTodo(Long employeeId, Long appointmentId) {
                notifications.add("todo:" + employeeId);
            }

            @Override
            public void approvalResult(Long appointmentId, boolean passed, String reason) {
                notifications.add("result:" + passed);
            }

            @Override
            public void reminder(Long employeeId, Long taskId) {
                notifications.add("remind:" + employeeId);
            }
        };
        DispatchTrigger dispatch = a -> dispatches.add(a.getAppointmentNo());

        AppProperties gateProps = new AppProperties();
        AppointmentGateService gateSelection = new AppointmentGateService(
                new InMemoryAppointmentGateRepository(),
                new GateService(InMemoryGateRepository.withUnassigned(), gateProps));

        DeviceQrConfigService qrConfig = new DeviceQrConfigService(null, null, null) {
            @Override
            public int requestParamSync() {
                return 0;
            }
        };

        service = new ApprovalService(configRepo, templateRepo, taskRepo, actionRepo, appointmentRepo,
                new FakeVisitorRepo(), adminUserRepo, notify, ccPort, dispatch, gateSelection, qrConfig, flowRepo);

        appointment = new Appointment();
        appointment.setAppointmentNo("A2026111600001");
        appointment.setVisitorId(1L);
        appointment.setVisitEmployeeId(5L);
        appointment.setStartTime(LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(9, 0)));
        appointment.setEndTime(LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(11, 0)));
        appointment.setStatus(Appointment.PENDING_L1);
        appointmentRepo.insert(appointment);
    }

    private long onlyTaskId() {
        return taskRepo.store.keySet().iterator().next();
    }

    /* ---------------- 用例（单级审批：管理端审核） ---------------- */

    @Test
    @DisplayName("启动审批：生成一条管理端角色任务，不再指派给员工")
    void startCreatesRoleTask() {
        service.start(appointment);

        assertEquals(1, taskRepo.store.size());
        ApprovalTask task = taskRepo.store.get(onlyTaskId());
        assertEquals(ApprovalTask.TYPE_ROLE, task.assigneeType());
        assertEquals("GUARD", task.assigneeRole());
        assertEquals(1, task.level());
        assertEquals(Appointment.PENDING_L1, appointment.getStatus());
        assertEquals(onlyTaskId(), appointment.getCurrentTaskId());
    }

    @Test
    @DisplayName("即使配了双级，也只生成一级任务（级数固定 1）")
    void alwaysSingleLevel() {
        configRepo.config = new ApprovalConfig(1L, "GLOBAL", 2, null, List.of(), "GUARD", 30, 2);
        service.start(appointment);

        assertEquals(1, taskRepo.store.size());
        assertEquals(1, taskRepo.store.get(onlyTaskId()).level());
        assertEquals(Appointment.PENDING_L1, appointment.getStatus());
    }

    @Test
    @DisplayName("管理端审核通过：预约变已通过并触发下发")
    void passDispatches() {
        service.start(appointment);
        ApprovalService.PassResult result =
                service.guardPass(onlyTaskId(), 99L, List.of("GUARD"), "同意", null);

        assertEquals(Appointment.APPROVED, appointment.getStatus());
        assertTrue(result.dispatchStarted());
        assertEquals(List.of("A2026111600001"), dispatches);
        assertTrue(notifications.contains("result:true"));
    }

    @Test
    @DisplayName("没有审核角色的人不能通过（3006）")
    void roleCheck() {
        service.start(appointment);
        BusinessException denied = assertThrows(BusinessException.class,
                () -> service.guardPass(onlyTaskId(), 99L, List.of("VISITOR_ADMIN"), "放行", null));
        assertEquals(ErrorCode.NOT_TASK_OWNER.code(), denied.getCode());
        assertFalse(dispatches.contains("A2026111600001"));
    }

    @Test
    @DisplayName("驳回必须填原因；驳回后不下发")
    void rejectNeedsComment() {
        service.start(appointment);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.guardReject(onlyTaskId(), 99L, List.of("GUARD"), " "));
        assertEquals(ErrorCode.PARAM_MISSING.code(), e.getCode());

        service.guardReject(onlyTaskId(), 99L, List.of("GUARD"), "人证不符");
        assertEquals(Appointment.REJECTED, appointment.getStatus());
        assertEquals("人证不符", appointment.getRejectReason());
        assertFalse(dispatches.contains("A2026111600001"), "驳回后不得下发");
    }

    @Test
    @DisplayName("同一任务不能重复审核（3005）")
    void cannotHandleTwice() {
        service.start(appointment);
        service.guardPass(onlyTaskId(), 99L, List.of("GUARD"), null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.guardPass(onlyTaskId(), 99L, List.of("GUARD"), null, null));
        assertEquals(ErrorCode.TASK_ALREADY_HANDLED.code(), e.getCode());
    }

    @Test
    @DisplayName("催办：按配置间隔限流")
    void remindLimit() {
        service.start(appointment);

        int remaining = service.remind(onlyTaskId(), 99L);
        assertEquals(1, remaining, "默认上限 2 次，第一次后剩 1 次");

        BusinessException again = assertThrows(BusinessException.class,
                () -> service.remind(onlyTaskId(), 99L));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), again.getCode(), "30 分钟内不能重复催办");
    }

    @Test
    @DisplayName("配置：级数强制 1，角色为空时用默认 GUARD")
    void configForcedSingleLevel() {
        service.updateConfig(new ApprovalConfig(1L, "GLOBAL", 2, null, List.of(), "", 15, 3));

        ApprovalConfig saved = service.getConfig();
        assertEquals(1, saved.approvalLevel());
        assertFalse(saved.twoLevel());
        assertEquals("GUARD", saved.roleOrDefault());
        assertEquals(15, saved.reminderIntervalMin());
        assertEquals(3, saved.reminderMaxPerTask());
    }

    /* ---------------- 多级审批 / 会签 / 抄送（V24） ---------------- */

    private ApprovalNode node(long id, long templateId, int seq, String name, String role,
                              String mode, boolean allowGates, boolean allowQr) {
        return new ApprovalNode(id, templateId, seq, name, ApprovalNode.TYPE_ROLE, role, mode,
                allowGates, allowQr);
    }

    @Test
    @DisplayName("多级审批：一级通过进入二级，二级通过才下发；末级节点办结后抄送")
    void multiLevelFlow() {
        ApprovalNode first = node(11L, 9L, 1, "保安审核", "GUARD", "SINGLE", true, true);
        ApprovalNode second = node(12L, 9L, 2, "行政复核", "ADMIN", "SINGLE", false, false);
        templateRepo.template = new ApprovalTemplate(9L, "默认审批流程", "GLOBAL", true, null,
                List.of(first, second), List.of(ApprovalCc.toRole(9L, 12L, "ADMIN")));

        service.start(appointment);
        assertTrue(ccs.isEmpty(), "提交时没配抄送就不发");
        ApprovalTask task1 = taskRepo.store.get(onlyTaskId());
        assertEquals("保安审核", task1.nodeName());

        ApprovalService.PassResult r1 = service.guardPass(task1.id(), 99L, List.of("GUARD"), "ok", null);
        assertFalse(r1.dispatchStarted());
        assertEquals(Appointment.PENDING_L2, appointment.getStatus());
        assertTrue(dispatches.isEmpty(), "一级通过不能直接下发");

        List<ApprovalTask> pending = taskRepo.findPendingByAppointment(appointment.getId());
        assertEquals(1, pending.size());
        ApprovalTask task2 = pending.get(0);
        assertEquals("行政复核", task2.nodeName());
        assertEquals("ADMIN", task2.assigneeRole());

        ApprovalService.PassResult r2 = service.guardPass(task2.id(), 99L, List.of("ADMIN"), "ok", null);
        assertTrue(r2.dispatchStarted());
        assertEquals(Appointment.APPROVED, appointment.getStatus());
        assertTrue(dispatches.contains("A2026111600001"), "末级通过才下发");
        assertTrue(ccs.stream().anyMatch(s -> s.contains("行政复核")), "节点办结后按配置抄送 ADMIN");
    }

    @Test
    @DisplayName("C-79：即使节点配成会签，也只建一条角色任务，任一人通过即流转")
    void allModeDegradesToAny() {
        ApprovalNode first = node(21L, 10L, 1, "全员审核", "GUARD", "ALL", true, true);
        ApprovalNode second = node(22L, 10L, 2, "终审", "ADMIN", "ANY", true, true);
        templateRepo.template = new ApprovalTemplate(10L, "旧会签流程", "GLOBAL", true, null,
                List.of(first, second), List.of());

        service.start(appointment);
        List<ApprovalTask> pending = taskRepo.findPendingByAppointment(appointment.getId());
        assertEquals(1, pending.size(), "或签只建一条角色任务");
        assertEquals(ApprovalTask.TYPE_ROLE, pending.get(0).assigneeType());

        service.guardPass(pending.get(0).id(), 101L, List.of("GUARD"), "ok", null);
        ApprovalTask next = taskRepo.findPendingByAppointment(appointment.getId()).get(0);
        assertEquals("终审", next.nodeName(), "一人通过即进入下一节点");
    }

    @Test
    @DisplayName("C-82：提交后改模板不影响在途单（流程快照生效）")
    void inFlightFlowFrozenAfterTemplateChange() {
        ApprovalNode first = node(51L, 13L, 1, "审批", "APPROVER", "ANY", true, true);
        ApprovalNode second = node(52L, 13L, 2, "领导复核", "LEADER", "ANY", true, true);
        templateRepo.template = new ApprovalTemplate(13L, "两级流程", "GLOBAL", true, null,
                List.of(first, second), List.of());

        service.start(appointment);

        // 提交后管理员把模板改成三级，并把第 2 层角色换掉
        ApprovalNode changedSecond = node(52L, 13L, 2, "保安复核", "GUARD", "ANY", true, true);
        ApprovalNode addedThird = node(53L, 13L, 3, "领导终审", "LEADER", "ANY", true, true);
        templateRepo.template = new ApprovalTemplate(13L, "三级流程", "GLOBAL", true, null,
                List.of(node(51L, 13L, 1, "审批", "APPROVER", "ANY", true, true), changedSecond, addedThird),
                List.of());

        service.guardPass(onlyTaskId(), 99L, List.of("APPROVER"), "ok", null);

        List<ApprovalTask> pending = taskRepo.findPendingByAppointment(appointment.getId());
        assertEquals(1, pending.size());
        assertEquals("领导复核", pending.get(0).nodeName(), "仍走提交时的第 2 层");
        assertEquals("LEADER", pending.get(0).assigneeRole(), "角色不被新模板改掉");

        service.guardPass(pending.get(0).id(), 99L, List.of("LEADER"), "ok", null);

        assertEquals(Appointment.APPROVED, appointment.getStatus(), "两级走完即终审");
        assertTrue(dispatches.contains("A2026111600001"), "不会补走新增的第 3 级");
    }

    @Test
    @DisplayName("提交时抄送：流程启动就通知抄送角色")
    void submitCcNotified() {
        ApprovalNode first = node(31L, 11L, 1, "保安审核", "GUARD", "SINGLE", true, true);
        templateRepo.template = new ApprovalTemplate(11L, "抄送流程", "GLOBAL", true, null,
                List.of(first), List.of(ApprovalCc.toRole(11L, null, "ADMIN")));

        service.start(appointment);

        assertEquals(1, ccs.size());
        assertTrue(ccs.get(0).startsWith("ADMIN@"), "抄送对象是 ADMIN 角色");
    }

    @Test
    @DisplayName("节点限制：不允许批二维码的节点，即使传了 qrApproved 也不生效")
    void nodeRestrictionIgnoresQr() {
        ApprovalNode first = node(41L, 12L, 1, "只读审核", "GUARD", "SINGLE", true, false);
        templateRepo.template = new ApprovalTemplate(12L, "受限流程", "GLOBAL", true, null,
                List.of(first), List.of());
        appointment.setQrRequested(true);

        service.start(appointment);
        service.guardPass(onlyTaskId(), 99L, List.of("GUARD"), "ok", null, true);

        assertFalse(appointment.isQrApproved(), "节点不允许批二维码时不能写入同意");
        assertTrue(dispatches.contains("A2026111600001"));
    }
}