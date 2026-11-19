package com.fangke.visitor.notify.service;

import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.notify.model.Notification;
import com.fangke.visitor.notify.model.NotifyTemplates;
import com.fangke.visitor.notify.repository.NotificationRepository;
import com.fangke.visitor.notify.sms.SmsSender;
import com.fangke.visitor.security.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通知中心：站内/短信、去重、失败重试、已读。
 */
class NotifyServiceTest {

    private FakeRepo repo;
    private FakeSms sms;
    private EmployeeRepository employeeRepo;
    private VisitorRepository visitorRepo;
    private CryptoService crypto;
    private NotifyService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");
        crypto = new CryptoService(props);

        repo = new FakeRepo();
        sms = new FakeSms();
        employeeRepo = mock(EmployeeRepository.class);
        visitorRepo = mock(VisitorRepository.class);

        Employee employee = new Employee();
        employee.setId(5L);
        employee.setName("张主管");
        employee.setPhoneEnc(crypto.encrypt("13800001234"));
        when(employeeRepo.findById(5L)).thenReturn(Optional.of(employee));
        when(employeeRepo.findById(anyLong())).thenReturn(Optional.of(employee));

        Visitor visitor = new Visitor();
        visitor.setId(9L);
        visitor.setName("访客甲");
        visitor.setPhoneEnc(crypto.encrypt("13900005678"));
        when(visitorRepo.findById(anyLong())).thenReturn(Optional.of(visitor));

        service = new NotifyService(repo, sms, employeeRepo, visitorRepo, crypto);
    }

    private NotifyTemplates.Content content() {
        return NotifyTemplates.approvalTodo("访客甲", "A2026111600001", "11-16 09:00-18:00", "送货");
    }

    @Test
    @DisplayName("员工通知：站内立即已发，短信走通道并标记已发")
    void notifyEmployeeBothChannels() {
        boolean inserted = service.notifyEmployee(5L, NotifyTemplates.APPROVAL_TODO, content(),
                "APPROVAL", "A2026111600001", null, true);

        assertTrue(inserted);
        assertEquals(2, repo.rows.size());
        Notification inApp = repo.rows.stream()
                .filter(n -> n.channel() == Notification.CHANNEL_INAPP).findFirst().orElseThrow();
        assertEquals(Notification.STATUS_SENT, inApp.status());
        assertNotNull(inApp.sentAt());

        Notification smsRow = repo.rows.stream()
                .filter(n -> n.channel() == Notification.CHANNEL_SMS).findFirst().orElseThrow();
        assertEquals(Notification.STATUS_SENT, smsRow.status());
        assertEquals(1, sms.sent.size());
        assertTrue(sms.sent.get(0).contains("13800001234"));
    }

    @Test
    @DisplayName("去重：同一事件重复触发只留一条站内消息")
    void dedupSameEvent() {
        service.notifyEmployee(5L, NotifyTemplates.APPROVAL_TODO, content(),
                "APPROVAL", "A2026111600001", null, false);
        boolean second = service.notifyEmployee(5L, NotifyTemplates.APPROVAL_TODO, content(),
                "APPROVAL", "A2026111600001", null, false);

        assertFalse(second);
        assertEquals(1, repo.rows.size());
    }

    @Test
    @DisplayName("短信通道失败：落库标记失败并累计重试次数，站内消息不受影响")
    void smsFailureDoesNotBreakInApp() {
        sms.ok = false;

        service.notifyVisitor(9L, NotifyTemplates.APPROVAL_PASSED,
                NotifyTemplates.approvalPassed("访客甲", "11-16 09:00-18:00"),
                "APPOINTMENT", "A2026111600001", null, true);

        Notification inApp = repo.rows.stream()
                .filter(n -> n.channel() == Notification.CHANNEL_INAPP).findFirst().orElseThrow();
        Notification smsRow = repo.rows.stream()
                .filter(n -> n.channel() == Notification.CHANNEL_SMS).findFirst().orElseThrow();
        assertEquals(Notification.STATUS_SENT, inApp.status());
        assertEquals(Notification.STATUS_FAILED, smsRow.status());
        assertEquals(1, smsRow.retryCount());
    }

    @Test
    @DisplayName("短信重试：失败记录重发成功后置已发")
    void retryFailedSms() {
        sms.ok = false;
        service.notifyEmployee(5L, NotifyTemplates.APPROVAL_TODO, content(),
                "APPROVAL", "A2026111600001", null, true);

        sms.ok = true;
        int success = service.retryFailed(10);

        assertEquals(1, success);
        Notification smsRow = repo.rows.stream()
                .filter(n -> n.channel() == Notification.CHANNEL_SMS).findFirst().orElseThrow();
        assertEquals(Notification.STATUS_SENT, smsRow.status());
    }

    @Test
    @DisplayName("未读数与已读：标记单条与全部")
    void unreadAndMarkRead() {
        service.notifyEmployee(5L, NotifyTemplates.APPROVAL_TODO, content(),
                "APPROVAL", "A2026111600001", "1", false);
        service.notifyEmployee(5L, NotifyTemplates.APPROVAL_REMIND,
                NotifyTemplates.approvalRemind("访客甲", "A2026111600001", "11-16 09:00-18:00"),
                "APPROVAL", "A2026111600001", "2", false);

        assertEquals(2, service.unreadCount(Notification.RECEIVER_EMPLOYEE, 5L));

        Long firstId = repo.rows.get(0).id();
        assertTrue(service.markRead(Notification.RECEIVER_EMPLOYEE, 5L, firstId));
        assertEquals(1, service.unreadCount(Notification.RECEIVER_EMPLOYEE, 5L));

        assertEquals(1, service.markAllRead(Notification.RECEIVER_EMPLOYEE, 5L));
        assertEquals(0, service.unreadCount(Notification.RECEIVER_EMPLOYEE, 5L));
    }

    @Test
    @DisplayName("列表：分页与未读过滤")
    void listPaging() {
        for (int i = 1; i <= 3; i++) {
            service.notifyEmployee(5L, NotifyTemplates.APPROVAL_TODO, content(),
                    "APPROVAL", "A202611160000" + i, null, false);
        }
        PageResult<Notification> page = service.list(Notification.RECEIVER_EMPLOYEE, 5L, true, 1, 2);

        assertEquals(3, page.getTotal());
        assertEquals(2, page.getList().size());
        assertEquals(3, repo.rows.size());
    }

    /* ---------------- 假实现 ---------------- */

    static class FakeSms implements SmsSender {
        boolean ok = true;
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean send(String phone, String content) {
            sent.add(phone);
            return ok;
        }

        @Override
        public String provider() {
            return "fake";
        }
    }

    static class FakeRepo implements NotificationRepository {

        final List<Notification> rows = new ArrayList<>();
        private long seq = 0;

        @Override
        public Long insertIfAbsent(Notification n) {
            if (rows.stream().anyMatch(r -> r.dedupKey().equals(n.dedupKey()))) {
                return null;
            }
            long id = ++seq;
            rows.add(new Notification(id, n.receiverType(), n.receiverId(), n.channel(),
                    n.templateCode(), n.title(), n.content(), n.bizType(), n.bizNo(), n.status(),
                    n.readAt(), n.sentAt(), n.retryCount(), n.errorMsg(), n.dedupKey(),
                    LocalDateTime.now()));
            return id;
        }

        @Override
        public Optional<Notification> findById(Long id) {
            return rows.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public List<Notification> list(int receiverType, Long receiverId, Boolean unreadOnly,
                                       int offset, int limit) {
            return rows.stream()
                    .filter(r -> r.receiverType() == receiverType && r.receiverId().equals(receiverId))
                    .filter(r -> !Boolean.TRUE.equals(unreadOnly) || r.readAt() == null)
                    .sorted(Comparator.comparing(Notification::id).reversed())
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public long count(int receiverType, Long receiverId, Boolean unreadOnly) {
            return rows.stream()
                    .filter(r -> r.receiverType() == receiverType && r.receiverId().equals(receiverId))
                    .filter(r -> !Boolean.TRUE.equals(unreadOnly) || r.readAt() == null)
                    .count();
        }

        @Override
        public long unreadCount(int receiverType, Long receiverId) {
            return rows.stream()
                    .filter(r -> r.receiverType() == receiverType && r.receiverId().equals(receiverId))
                    .filter(r -> r.readAt() == null)
                    .count();
        }

        @Override
        public int markRead(Long id, int receiverType, Long receiverId, LocalDateTime at) {
            for (int i = 0; i < rows.size(); i++) {
                Notification r = rows.get(i);
                if (r.id().equals(id) && r.receiverType() == receiverType
                        && r.receiverId().equals(receiverId) && r.readAt() == null) {
                    rows.set(i, copy(r, at, r.status(), r.retryCount()));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int markAllRead(int receiverType, Long receiverId, LocalDateTime at) {
            int count = 0;
            for (int i = 0; i < rows.size(); i++) {
                Notification r = rows.get(i);
                if (r.receiverType() == receiverType && r.receiverId().equals(receiverId)
                        && r.readAt() == null) {
                    rows.set(i, copy(r, at, r.status(), r.retryCount()));
                    count++;
                }
            }
            return count;
        }

        @Override
        public void markSent(Long id, LocalDateTime at) {
            replace(id, n -> copy(n, n.readAt(), Notification.STATUS_SENT, n.retryCount()));
        }

        @Override
        public void markFailed(Long id, String errorMsg) {
            replace(id, n -> copy(n, n.readAt(), Notification.STATUS_FAILED, n.retryCount() + 1));
        }

        @Override
        public List<Notification> findRetryable(int limit) {
            return rows.stream()
                    .filter(n -> n.status() == Notification.STATUS_FAILED && n.retryCount() < 3)
                    .limit(limit).toList();
        }

        @Override
        public List<Notification> listForAdmin(String templateCode, Integer receiverType,
                                               Integer status, int offset, int limit) {
            return list(rows.isEmpty() ? 0 : rows.get(0).receiverType(),
                    rows.isEmpty() ? 0L : rows.get(0).receiverId(), null, offset, limit);
        }

        @Override
        public long countForAdmin(String templateCode, Integer receiverType, Integer status) {
            return rows.size();
        }

        private void replace(Long id, java.util.function.UnaryOperator<Notification> mapper) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id().equals(id)) {
                    rows.set(i, mapper.apply(rows.get(i)));
                    return;
                }
            }
        }

        private Notification copy(Notification n, LocalDateTime readAt, int status, int retryCount) {
            return new Notification(n.id(), n.receiverType(), n.receiverId(), n.channel(),
                    n.templateCode(), n.title(), n.content(), n.bizType(), n.bizNo(), status,
                    readAt, n.sentAt() == null ? LocalDateTime.now() : n.sentAt(), retryCount,
                    n.errorMsg(), n.dedupKey(), n.createdAt());
        }
    }
}
