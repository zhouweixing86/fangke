package com.fangke.visitor.admin.service;

import com.fangke.visitor.admin.repository.AdminBlacklistRepository;
import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.AppointmentService;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.dispatch.service.DispatchService;
import com.fangke.visitor.qr.service.QrTicketService;
import com.fangke.visitor.security.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 黑名单管理：校验、三件套落库、重复拉黑、失效与恢复。
 */
class BlacklistAdminServiceTest {

    /** 校验位合法的样例证件号（与员工模块测试一致）。 */
    private static final String ID_CARD = "110101199003074477";

    private FakeRepo repo;
    private CryptoService crypto;
    private VisitorRepository visitorRepo;
    private AppointmentRepository appointmentRepo;
    private AppointmentService appointmentService;
    private DispatchService dispatchService;
    private QrTicketService qrTicketService;
    private BlacklistAdminService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");
        crypto = new CryptoService(props);
        repo = new FakeRepo();
        visitorRepo = mock(VisitorRepository.class);
        appointmentRepo = mock(AppointmentRepository.class);
        appointmentService = mock(AppointmentService.class);
        dispatchService = mock(DispatchService.class);
        qrTicketService = mock(QrTicketService.class);
        service = new BlacklistAdminService(repo, crypto, visitorRepo, appointmentRepo,
                appointmentService, dispatchService, qrTicketService);
    }

    @Test
    @DisplayName("新增：密文 + HMAC + 脱敏三件套一起落库")
    void createStoresCipherHmacAndMask() {
        Long id = service.create(1, ID_CARD, "上次闹事", null, null, 7L);

        assertEquals(1L, id);
        assertEquals(1, repo.rows.size());
        FakeRepo.Inserted row = repo.rows.get(0);
        assertEquals("110101********4477", row.mask);
        assertEquals(ID_CARD, crypto.decrypt(row.enc));
        assertEquals(crypto.hmac(ID_CARD), row.hmac);
        assertNotEquals(ID_CARD, new String(row.enc));
        assertEquals(7L, row.createdBy);
    }

    @Test
    @DisplayName("证件号校验位错误：拒绝，不落库")
    void rejectsBadIdCard() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(1, "110101199001011234", "原因", null, null, 1L));
        assertEquals(ErrorCode.ID_CARD_INVALID.code(), e.getCode());
        assertTrue(repo.rows.isEmpty());
    }

    @Test
    @DisplayName("手机号类型：同样加密并脱敏为 138****8000")
    void createPhoneTarget() {
        service.create(2, "13800138000", "骚扰", null, null, 1L);
        assertEquals("138****8000", repo.rows.get(0).mask);
        assertEquals(2, repo.rows.get(0).type);
    }

    @Test
    @DisplayName("重复拉黑：提示已存在，且不重复落库")
    void rejectsDuplicate() {
        service.create(1, ID_CARD, "原因", null, null, 1L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(1, ID_CARD, "原因2", null, null, 1L));
        assertTrue(e.getMessage().contains("已在黑名单中"));
        assertEquals(1, repo.rows.size());
    }

    @Test
    @DisplayName("原因必填、超长拒绝")
    void validatesReason() {
        assertEquals(ErrorCode.PARAM_MISSING.code(), assertThrows(BusinessException.class,
                () -> service.create(1, ID_CARD, "  ", null, null, 1L)).getCode());
        assertEquals(ErrorCode.PARAM_TOO_LONG.code(), assertThrows(BusinessException.class,
                () -> service.create(1, ID_CARD, "x".repeat(256), null, null, 1L)).getCode());
    }

    @Test
    @DisplayName("有效期开始晚于结束：拒绝")
    void validatesRange() {
        LocalDateTime from = LocalDateTime.of(2026, 12, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create(1, ID_CARD, "原因", from, to, 1L)).getCode());
    }

    @Test
    @DisplayName("无效类型：拒绝")
    void rejectsUnknownType() {
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.create(9, ID_CARD, "原因", null, null, 1L)).getCode());
    }

    @Test
    @DisplayName("失效/恢复：状态可来回切换，记录保留")
    void toggleStatus() {
        Long id = service.create(1, ID_CARD, "原因", null, null, 1L);

        service.updateStatus(id, 0);
        assertEquals(0, repo.findById(id).orElseThrow().status());

        service.updateStatus(id, 1);
        assertEquals(1, repo.findById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("状态非法或记录不存在：拒绝")
    void rejectsBadStatus() {
        Long id = service.create(1, ID_CARD, "原因", null, null, 1L);
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.updateStatus(id, 2)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.updateStatus(999L, 0)).getCode());
    }

    /* ---------------- 立即生效（BR-B-003） ---------------- */

    @Test
    @DisplayName("立即生效：没有访客档案时如实说明，不动在途数据")
    void enforceWithoutVisitor() {
        Long id = service.create(1, ID_CARD, "原因", null, null, 1L);
        when(visitorRepo.findByIdCardHmac(anyString())).thenReturn(Optional.empty());

        BlacklistAdminService.EnforceResult result = service.enforce(id, 1L);

        assertEquals(0, result.appointments());
        assertTrue(result.message().contains("暂无访客档案"));
        verify(appointmentRepo, never()).findActiveByVisitor(anyLong());
    }

    @Test
    @DisplayName("立即生效：待审批撤回、已通过撤销授权并作废通行码")
    void enforceRevokesActiveAppointments() {
        Long id = service.create(1, ID_CARD, "原因", null, null, 1L);
        Visitor visitor = new Visitor();
        visitor.setId(9L);
        when(visitorRepo.findByIdCardHmac(anyString())).thenReturn(Optional.of(visitor));

        Appointment pending = new Appointment();
        pending.setId(1L);
        pending.setAppointmentNo("A1");
        pending.setStatus(Appointment.PENDING_L1);
        Appointment approved = new Appointment();
        approved.setId(2L);
        approved.setAppointmentNo("A2");
        approved.setStatus(Appointment.GRANTED);
        when(appointmentRepo.findActiveByVisitor(9L)).thenReturn(List.of(pending, approved));

        BlacklistAdminService.EnforceResult result = service.enforce(id, 1L);

        assertEquals(2, result.appointments());
        assertEquals(1, result.cancelled());
        assertEquals(1, result.revoked());
        assertEquals(0, result.failed());
        verify(appointmentService).cancel("A1", "命中黑名单，管理员立即生效");
        verify(qrTicketService).revoke("A2");
        verify(dispatchService).revoke(2L, 1L);
    }

    @Test
    @DisplayName("立即生效：已通过但还没有授权记录 → 直接置撤销")
    void enforceWithoutGrantMarksRevoked() {
        Long id = service.create(1, ID_CARD, "原因", null, null, 1L);
        Visitor visitor = new Visitor();
        visitor.setId(9L);
        when(visitorRepo.findByIdCardHmac(anyString())).thenReturn(Optional.of(visitor));
        Appointment approved = new Appointment();
        approved.setId(3L);
        approved.setAppointmentNo("A3");
        approved.setStatus(Appointment.APPROVED);
        when(appointmentRepo.findActiveByVisitor(9L)).thenReturn(List.of(approved));
        when(dispatchService.revoke(3L, 1L))
                .thenThrow(new BusinessException(ErrorCode.PARAM_INVALID, "该预约单没有授权记录"));

        BlacklistAdminService.EnforceResult result = service.enforce(id, 1L);

        assertEquals(1, result.revoked());
        assertEquals(0, result.failed());
        verify(appointmentRepo).updateStatus(3L, Appointment.REVOKED);
    }

    @Test
    @DisplayName("立即生效：已失效的黑名单拒绝执行")
    void enforceRejectsInactive() {
        Long id = service.create(1, ID_CARD, "原因", null, null, 1L);
        service.updateStatus(id, 0);

        BusinessException e = assertThrows(BusinessException.class, () -> service.enforce(id, 1L));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
        assertTrue(e.getMessage().contains("已失效"));
    }

    /* ---------------- 假仓储 ---------------- */

    static class FakeRepo implements AdminBlacklistRepository {

        static class Inserted {
            int type;
            byte[] enc;
            String hmac;
            String mask;
            String reason;
            Long createdBy;
        }

        final List<Inserted> rows = new ArrayList<>();
        final List<Row> view = new ArrayList<>();
        private long seq = 0;

        @Override
        public List<Row> search(String keyword, Integer status, int offset, int limit) {
            return view;
        }

        @Override
        public long count(String keyword, Integer status) {
            return view.size();
        }

        @Override
        public Optional<Row> findById(Long id) {
            return view.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<String> findTargetHmac(Long id) {
            return Optional.of("unit-target-hmac");
        }

        @Override
        public boolean existsEffective(int targetType, String targetHmac) {
            return rows.stream().anyMatch(r -> r.type == targetType && r.hmac.equals(targetHmac));
        }

        @Override
        public long insert(int targetType, byte[] targetEnc, String targetHmac, String targetMask,
                           String reason, LocalDateTime validFrom, LocalDateTime validTo, Long createdBy) {
            Inserted inserted = new Inserted();
            inserted.type = targetType;
            inserted.enc = targetEnc;
            inserted.hmac = targetHmac;
            inserted.mask = targetMask;
            inserted.reason = reason;
            inserted.createdBy = createdBy;
            rows.add(inserted);

            long id = ++seq;
            view.add(new Row(id, targetType, targetMask, reason, validFrom, validTo, 1,
                    createdBy, LocalDateTime.now()));
            return id;
        }

        @Override
        public void updateStatus(Long id, int status) {
            for (int i = 0; i < view.size(); i++) {
                Row r = view.get(i);
                if (r.id().equals(id)) {
                    view.set(i, new Row(r.id(), r.targetType(), r.targetMask(), r.reason(),
                            r.validFrom(), r.validTo(), status, r.createdBy(), r.createdAt()));
                }
            }
        }
    }
}
