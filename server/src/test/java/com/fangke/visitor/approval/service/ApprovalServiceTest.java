package com.fangke.visitor.approval.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.InMemoryAppointmentGateRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.AppointmentGateService;
import com.fangke.visitor.approval.model.ApprovalConfig;
import com.fangke.visitor.approval.model.ApprovalTask;
import com.fangke.visitor.approval.repository.ApprovalActionRepository;
import com.fangke.visitor.approval.repository.ApprovalConfigRepository;
import com.fangke.visitor.approval.repository.ApprovalTaskRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.config.AppProperties;
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

    static class FakeTaskRepo implements ApprovalTaskRepository {
        final Map<Long, ApprovalTask> store = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong();

        @Override
        public long insert(ApprovalTask t) {
            long id = seq.incrementAndGet();
            store.put(id, new ApprovalTask(id, t.appointmentId(), t.level(), t.assigneeId(), t.status(),
                    0, null, null, LocalDateTime.now(), t.assigneeType(), t.assigneeRole()));
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
        public long countPendingByRole(String role) {
            return store.values().stream()
                    .filter(t -> role.equals(t.assigneeRole()) && t.status() == 1 && t.roleTask())
                    .count();
        }

        @Override
        public List<TodoRow> findTodo(Long assigneeId, int offset, int limit) {
            return List.of();
        }

        @Override
        public List<TodoRow> findTodoByRole(String role, int offset, int limit) {
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
            store.put(id, new ApprovalTask(t.id(), t.appointmentId(), t.level(), t.assigneeId(), t.status(),
                    t.reminderCount() + 1, LocalDateTime.now(), t.finishedAt(), t.createdAt(),
                    t.assigneeType(), t.assigneeRole()));
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

    private FakeConfigRepo configRepo;
    private FakeTaskRepo taskRepo;
    private FakeActionRepo actionRepo;
    private FakeAppointmentRepo appointmentRepo;
    private final List<String> notifications = new ArrayList<>();
    private final List<String> dispatches = new ArrayList<>();
    private ApprovalService service;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        configRepo = new FakeConfigRepo();
        taskRepo = new FakeTaskRepo();
        actionRepo = new FakeActionRepo();
        appointmentRepo = new FakeAppointmentRepo();

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

        service = new ApprovalService(configRepo, taskRepo, actionRepo, appointmentRepo,
                new FakeVisitorRepo(), notify, dispatch, gateSelection);

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

    /* ---------------- 用例 ---------------- */

    @Test
    @DisplayName("启动审批：按被访人生成一级任务并通知")
    void startCreatesLevel1Task() {
        service.start(appointment);

        assertEquals(1, taskRepo.store.size());
        ApprovalTask task = taskRepo.store.get(onlyTaskId());
        assertEquals(5L, task.assigneeId());
        assertEquals(1, task.level());
        assertEquals(Appointment.PENDING_L1, appointment.getStatus());
        assertEquals(onlyTaskId(), appointment.getCurrentTaskId());
        assertTrue(notifications.contains("todo:5"));
    }

    @Test
    @DisplayName("被访人未匹配且无默认审批人：不建任务但不抛错（留在待审批）")
    void startWithoutApprover() {
        appointment.setVisitEmployeeId(null);
        service.start(appointment);

        assertTrue(taskRepo.store.isEmpty());
        assertEquals(Appointment.PENDING_L1, appointment.getStatus());
    }

    @Test
    @DisplayName("一级审批通过（单级）：预约单变已通过并触发下发")
    void singleLevelPass() {
        service.start(appointment);
        ApprovalService.PassResult result = service.pass(onlyTaskId(), 5L, "同意", null);

        assertEquals(0, result.status() == Appointment.APPROVED ? 0 : 1);
        assertEquals(Appointment.APPROVED, appointment.getStatus());
        assertTrue(result.dispatchStarted());
        assertEquals(List.of("A2026111600001"), dispatches);
        assertTrue(notifications.contains("result:true"));
    }

    @Test
    @DisplayName("双审：一级（被访人）通过后生成保安复核角色任务，不下发")
    void twoLevelPass() {
        configRepo.config = new ApprovalConfig(1L, "GLOBAL", 2, null, List.of(), "GUARD", 30, 2);
        service.start(appointment);

        ApprovalService.PassResult result = service.pass(onlyTaskId(), 5L, "同意", null);

        assertTrue(result.needSecondLevel());
        assertEquals(Appointment.PENDING_L2, appointment.getStatus());
        assertEquals(2, taskRepo.store.size());
        ApprovalTask second = taskRepo.store.get(appointment.getCurrentTaskId());
        assertEquals(ApprovalTask.TYPE_ROLE, second.assigneeType());
        assertEquals("GUARD", second.assigneeRole());
        assertFalse(dispatches.contains("A2026111600001"), "保安未复核前不能下发授权");
    }

    @Test
    @DisplayName("双审：保安复核通过后才下发授权")
    void guardReviewPass() {
        configRepo.config = new ApprovalConfig(1L, "GLOBAL", 2, null, List.of(), "GUARD", 30, 2);
        service.start(appointment);
        service.pass(onlyTaskId(), 5L, "同意", null);
        long guardTaskId = appointment.getCurrentTaskId();

        ApprovalService.PassResult result = service.guardPass(guardTaskId, 99L, List.of("GUARD"), "放行", null);

        assertTrue(result.dispatchStarted());
        assertEquals(Appointment.APPROVED, appointment.getStatus());
        assertTrue(dispatches.contains("A2026111600001"));
    }

    @Test
    @DisplayName("双审：员工端不能处理保安复核任务（3006）")
    void employeeCannotHandleGuardTask() {
        configRepo.config = new ApprovalConfig(1L, "GLOBAL", 2, null, List.of(), "GUARD", 30, 2);
        service.start(appointment);
        service.pass(onlyTaskId(), 5L, "同意", null);
        long guardTaskId = appointment.getCurrentTaskId();

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.pass(guardTaskId, 5L, "越权审批", null));
        assertEquals(ErrorCode.NOT_TASK_OWNER.code(), e.getCode());
    }

    @Test
    @DisplayName("双审：无复核角色的人不能通过；保安驳回则整单驳回")
    void guardRoleCheck() {
        configRepo.config = new ApprovalConfig(1L, "GLOBAL", 2, null, List.of(), "GUARD", 30, 2);
        service.start(appointment);
        service.pass(onlyTaskId(), 5L, "同意", null);
        long guardTaskId = appointment.getCurrentTaskId();

        BusinessException denied = assertThrows(BusinessException.class,
                () -> service.guardPass(guardTaskId, 99L, List.of("VISITOR_ADMIN"), "放行", null));
        assertEquals(ErrorCode.NOT_TASK_OWNER.code(), denied.getCode());

        service.guardReject(guardTaskId, 99L, List.of("GUARD"), "人证不符");
        assertEquals(Appointment.REJECTED, appointment.getStatus());
        assertEquals("人证不符", appointment.getRejectReason());
        assertFalse(dispatches.contains("A2026111600001"), "驳回后不得下发");
    }

    @Test
    @DisplayName("驳回必须填意见；驳回后其余待办失效")
    void reject() {
        service.start(appointment);
        BusinessException noComment = assertThrows(BusinessException.class,
                () -> service.reject(onlyTaskId(), 5L, " "));
        assertEquals(ErrorCode.PARAM_MISSING.code(), noComment.getCode());

        service.reject(onlyTaskId(), 5L, "当天不在");
        assertEquals(Appointment.REJECTED, appointment.getStatus());
        assertEquals("当天不在", appointment.getRejectReason());
        assertTrue(notifications.contains("result:false"));
    }

    @Test
    @DisplayName("非审批人操作被拒（3006）；已处理任务再操作被拒（3005）")
    void ownershipAndStatus() {
        service.start(appointment);
        BusinessException notOwner = assertThrows(BusinessException.class,
                () -> service.pass(onlyTaskId(), 99L, null, null));
        assertEquals(ErrorCode.NOT_TASK_OWNER.code(), notOwner.getCode());

        service.pass(onlyTaskId(), 5L, null, null);
        BusinessException handled = assertThrows(BusinessException.class,
                () -> service.pass(onlyTaskId(), 5L, null, null));
        assertEquals(ErrorCode.TASK_ALREADY_HANDLED.code(), handled.getCode());
    }

    @Test
    @DisplayName("转办：任务改派给他人")
    void transfer() {
        service.start(appointment);
        service.transfer(onlyTaskId(), 5L, 6L, "我在出差");

        assertEquals(6L, taskRepo.store.get(onlyTaskId()).assigneeId());
        assertTrue(notifications.contains("todo:6"));
    }

    @Test
    @DisplayName("加签：同层并行，全部通过才终审")
    void countersign() {
        service.start(appointment);
        service.countersign(onlyTaskId(), 5L, 6L);
        assertEquals(2, taskRepo.store.size());

        ApprovalService.PassResult first = service.pass(onlyTaskId(), 5L, null, null);
        assertEquals(Appointment.PENDING_L1, appointment.getStatus());
        assertFalse(first.dispatchStarted());

        long secondTask = taskRepo.store.keySet().stream().filter(id -> id != onlyTaskId()).findFirst().orElseThrow();
        service.pass(secondTask, 6L, null, null);
        assertEquals(Appointment.APPROVED, appointment.getStatus());
    }

    @Test
    @DisplayName("催办限流：同一任务按次数上限拒绝")
    void remindLimit() {
        service.start(appointment);
        long taskId = onlyTaskId();

        int remaining = service.remind(taskId, 5L);
        assertEquals(1, remaining);
        assertTrue(notifications.contains("remind:5"));

        // 刚催办过，间隔不足
        BusinessException tooFrequent = assertThrows(BusinessException.class,
                () -> service.remind(taskId, 5L));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), tooFrequent.getCode());

        // 把上次时间改早，再催一次达到上限
        ApprovalTask task = taskRepo.store.get(taskId);
        taskRepo.store.put(taskId, new ApprovalTask(task.id(), task.appointmentId(), task.level(),
                task.assigneeId(), task.status(), task.reminderCount(),
                LocalDateTime.now().minusHours(2), task.finishedAt(), task.createdAt(),
                task.assigneeType(), task.assigneeRole()));
        service.remind(taskId, 5L);

        BusinessException maxed = assertThrows(BusinessException.class,
                () -> service.remind(taskId, 5L));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.code(), maxed.getCode());
    }

    @Test
    @DisplayName("待办角标与列表")
    void todo() {
        service.start(appointment);
        assertEquals(1, service.todoCount(5L));
        assertEquals(0, service.todoCount(6L));

        PageResult<ApprovalService.TodoView> page = service.todoList(5L, 1, 10);
        assertEquals(1, page.getTotal());
    }

    @Test
    @DisplayName("配置校验：审批级数只能是 1 或 2；二级角色为空时回落 GUARD")
    void configValidation() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.updateConfig(
                new ApprovalConfig(1L, "GLOBAL", 3, null, List.of(), "GUARD", 30, 2)));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());

        ApprovalConfig saved = service.updateConfig(
                new ApprovalConfig(1L, "GLOBAL", 1, null, List.of(), null, 30, 2));
        assertFalse(saved.twoLevel());
        assertEquals("GUARD", saved.roleOrDefault(), "角色为空时回落 GUARD");
    }
}
