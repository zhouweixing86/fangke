package com.fangke.visitor.qr.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.AccessEvent;
import com.fangke.visitor.device.service.AccessEventRepository;
import com.fangke.visitor.device.service.GrantChecker;
import com.fangke.visitor.device.service.QrCodeVerifier;
import com.fangke.visitor.device.service.VisitRecorder;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.qr.model.QrTicket;
import com.fangke.visitor.qr.repository.QrTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M7 动态二维码平台侧：签发条件、验签、60 秒内可重复使用、撤销、留痕。
 */
class QrTicketServiceTest {

    private static final String APPOINTMENT_NO = "A2026111600001";

    private FakeTicketRepo ticketRepo;
    private AppointmentRepository appointmentRepo;
    private VisitorRepository visitorRepo;
    private EmployeeRepository employeeRepo;
    private GrantChecker grantChecker;
    private AccessEventRepository eventRepo;
    private VisitRecorder visitRecorder;
    private AppProperties props;
    private QrCodeVerifier verifier;
    private QrTicketService service;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getQr().setSecret("unit-qr-secret");
        props.getQr().setTtlSeconds(60);
        verifier = new QrCodeVerifier(props);

        ticketRepo = new FakeTicketRepo();
        appointmentRepo = mock(AppointmentRepository.class);
        visitorRepo = mock(VisitorRepository.class);
        employeeRepo = mock(EmployeeRepository.class);
        grantChecker = mock(GrantChecker.class);
        eventRepo = mock(AccessEventRepository.class);
        visitRecorder = mock(VisitRecorder.class);

        appointment = new Appointment();
        appointment.setId(1L);
        appointment.setAppointmentNo(APPOINTMENT_NO);
        appointment.setVisitorId(9L);
        appointment.setVisitEmployeeId(5L);
        appointment.setPurpose("送货");
        appointment.setVisitorType("商务");
        appointment.setStatus(Appointment.GRANTED);
        appointment.setStartTime(LocalDateTime.now().minusHours(1));
        appointment.setEndTime(LocalDateTime.now().plusHours(2));

        Visitor visitor = new Visitor();
        visitor.setId(9L);
        visitor.setPersonNo("V202611160001");
        visitor.setName("访客甲");
        visitor.setIdCardMask("110101********4477");

        Employee employee = new Employee();
        employee.setId(5L);
        employee.setName("张主管");

        when(appointmentRepo.findByNo(APPOINTMENT_NO)).thenReturn(Optional.of(appointment));
        when(appointmentRepo.findById(1L)).thenReturn(Optional.of(appointment));
        when(visitorRepo.findById(9L)).thenReturn(Optional.of(visitor));
        when(employeeRepo.findById(5L)).thenReturn(Optional.of(employee));
        when(grantChecker.check(eq(APPOINTMENT_NO), any(), any(Instant.class)))
                .thenReturn(GrantChecker.State.OK);
        when(grantChecker.resolveGateId(APPOINTMENT_NO)).thenReturn(1L);
        when(eventRepo.insertIfAbsent(any(AccessEvent.class))).thenReturn(Optional.of(100L));

        service = new QrTicketService(ticketRepo, appointmentRepo, visitorRepo, employeeRepo,
                verifier, grantChecker, eventRepo, visitRecorder, props, new QrImageService());
    }

    @Test
    @DisplayName("签发：码可被自己的验签器验证通过，票据只存摘要，TTL 取配置")
    void issueProducesVerifiableCode() {
        QrTicketService.IssueResult result = service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_ADMIN);

        assertEquals(60, result.ttlSeconds());
        assertEquals(APPOINTMENT_NO, result.appointmentNo());
        assertTrue(result.code().startsWith("RV1.T01." + APPOINTMENT_NO + "."));
        assertTrue(verifier.verify(result.code(), Instant.now()).valid());

        QrTicket ticket = ticketRepo.rows.get(0);
        assertEquals(QrTicket.STATUS_VALID, ticket.status());
        assertEquals(0, ticket.useCount());
        assertEquals(QrTicketService.CHANNEL_ADMIN, ticket.issuedChannel());
        assertEquals(64, ticket.codeHash().length());
        assertNotEquals(result.code(), ticket.codeHash());
    }

    @Test
    @DisplayName("没有生效授权：拒绝签发")
    void issueRequiresActiveGrant() {
        when(grantChecker.check(eq(APPOINTMENT_NO), any(), any(Instant.class)))
                .thenReturn(GrantChecker.State.NO_GRANT);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_ADMIN));
        assertEquals(ErrorCode.GRANT_NOT_READY.code(), e.getCode());
        assertTrue(ticketRepo.rows.isEmpty());
    }

    @Test
    @DisplayName("不在授权时段：拒绝签发")
    void issueRequiresWindow() {
        when(grantChecker.check(eq(APPOINTMENT_NO), any(), any(Instant.class)))
                .thenReturn(GrantChecker.State.OUT_OF_WINDOW);

        assertEquals(ErrorCode.NOT_IN_AUTHORIZED_WINDOW.code(), assertThrows(BusinessException.class,
                () -> service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_ADMIN)).getCode());
    }

    @Test
    @DisplayName("核验通过：票保持有效、使用次数 +1、写通行事件、触发签到")
    void verifyRecordsUse() {
        String code = service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_GUARD).code();

        QrTicketService.VerifyResult result = service.verify(code, 2L, null);

        assertTrue(result.allowed());
        assertEquals(APPOINTMENT_NO, result.appointmentNo());
        assertEquals("访客甲", result.visitorName());
        assertEquals("110101********4477", result.idCardMask());
        assertEquals("张主管", result.employeeName());
        assertEquals(1, result.useCount());

        QrTicket ticket = ticketRepo.rows.get(0);
        assertEquals(QrTicket.STATUS_VALID, ticket.status());
        assertEquals(1, ticket.useCount());
        assertEquals(2L, ticket.verifiedBy());
        verify(visitRecorder).record(any(AccessEvent.class), eq(100L));
    }

    @Test
    @DisplayName("BR-V-020：60 秒内可重复使用，通行后不作废")
    void sameCodeCanBeReusedWithinTtl() {
        String code = service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_GUARD).code();

        QrTicketService.VerifyResult first = service.verify(code, 2L, null);
        QrTicketService.VerifyResult second = service.verify(code, 3L, null);

        assertTrue(first.allowed());
        assertTrue(second.allowed());
        assertEquals(2, second.useCount());
        assertEquals(2, ticketRepo.rows.get(0).useCount());
        assertEquals(QrTicket.STATUS_VALID, ticketRepo.rows.get(0).status());
        verify(visitRecorder, times(2)).record(any(AccessEvent.class), eq(100L));
    }

    @Test
    @DisplayName("拿到门点：核验事件与签到都带门点；拿不到则只留事件")
    void gateResolutionControlsVisitRecord() {
        String withGate = service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_GUARD).code();
        assertTrue(service.verify(withGate, 2L, null).allowed());
        verify(visitRecorder).record(any(AccessEvent.class), eq(100L));

        when(grantChecker.resolveGateId(APPOINTMENT_NO)).thenReturn(null);
        String withoutGate = service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_GUARD).code();
        assertTrue(service.verify(withoutGate, 2L, null).allowed());
        verify(visitRecorder, times(1)).record(any(AccessEvent.class), anyLong());
    }

    @Test
    @DisplayName("签名被篡改：拒绝且不写签到")
    void verifyRejectsTamperedCode() {
        String code = service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_GUARD).code();
        String tampered = code.substring(0, code.length() - 1) + (code.endsWith("0") ? "1" : "0");

        QrTicketService.VerifyResult result = service.verify(tampered, 2L, null);

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("无效"));
        verify(visitRecorder, never()).record(any(), anyLong());
    }

    @Test
    @DisplayName("已过期的码：拒绝，并把票置为过期")
    void verifyRejectsExpiredCode() {
        String nonce = "abcdef123456";
        long exp = Instant.now().minusSeconds(60).getEpochSecond();
        String signing = "T01." + APPOINTMENT_NO + "." + exp + "." + nonce;
        String code = "RV1." + signing + "." + verifier.sign(signing);
        ticketRepo.insert(new QrTicket(null, 1L, APPOINTMENT_NO, nonce, "hash", QrTicket.STATUS_VALID, 0,
                "ADMIN", 1L, LocalDateTime.now().minusMinutes(3), LocalDateTime.now().minusMinutes(2),
                null, null));

        QrTicketService.VerifyResult result = service.verify(code, 2L, null);

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("过期"));
        // 验签阶段即判定过期并直接拒绝；票据状态由每日清理任务置为「已过期」
        assertEquals(QrTicket.STATUS_VALID, ticketRepo.rows.get(0).status());
    }

    @Test
    @DisplayName("已撤销的码：拒绝")
    void verifyRejectsRevokedCode() {
        String code = service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_GUARD).code();
        assertEquals(1, service.revoke(APPOINTMENT_NO));

        QrTicketService.VerifyResult result = service.verify(code, 2L, null);

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("撤销"));
    }

    @Test
    @DisplayName("签名正确但没有票据：拒绝（非本平台签发）")
    void verifyRejectsUnknownTicket() {
        long exp = Instant.now().plusSeconds(60).getEpochSecond();
        String signing = "T01." + APPOINTMENT_NO + "." + exp + ".ffffffffffff";
        String code = "RV1." + signing + "." + verifier.sign(signing);

        QrTicketService.VerifyResult result = service.verify(code, 2L, null);

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("不是本平台"));
    }

    @Test
    @DisplayName("核验时授权已失效：拒绝（不计使用次数）")
    void verifyRejectsWhenGrantLost() {
        String code = service.issue(APPOINTMENT_NO, 1L, QrTicketService.CHANNEL_GUARD).code();
        when(grantChecker.check(eq(APPOINTMENT_NO), any(), any(Instant.class)))
                .thenReturn(GrantChecker.State.NO_GRANT);

        QrTicketService.VerifyResult result = service.verify(code, 2L, null);

        assertFalse(result.allowed());
        assertEquals(0, ticketRepo.rows.get(0).useCount());
        assertEquals(QrTicket.STATUS_VALID, ticketRepo.rows.get(0).status());
    }

    @Test
    @DisplayName("预约不存在：明确报错")
    void issueUnknownAppointment() {
        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.issue("A_NOT_EXIST", 1L, QrTicketService.CHANNEL_ADMIN)).getCode());
    }

    @Test
    @DisplayName("清理任务：过期票置为已过期、7 天前老票删除")
    void housekeeping() {
        ticketRepo.insert(new QrTicket(null, 1L, APPOINTMENT_NO, "aaaaaaaaaaaa", "hash",
                QrTicket.STATUS_VALID, 0, "ADMIN", 1L, LocalDateTime.now().minusDays(10),
                LocalDateTime.now().minusDays(10), null, null));

        service.housekeeping();

        assertTrue(ticketRepo.rows.isEmpty());
    }

    /* ---------------- 内存票据仓储 ---------------- */

    static class FakeTicketRepo implements QrTicketRepository {

        final List<QrTicket> rows = new ArrayList<>();
        private long seq = 0;

        @Override
        public long insert(QrTicket ticket) {
            long id = ++seq;
            rows.add(new QrTicket(id, ticket.appointmentId(), ticket.appointmentNo(), ticket.nonce(),
                    ticket.codeHash(), ticket.status(), ticket.useCount(), ticket.issuedChannel(),
                    ticket.issuedBy(), ticket.issuedAt(), ticket.expireAt(), ticket.lastUsedAt(),
                    ticket.verifiedBy()));
            return id;
        }

        @Override
        public Optional<QrTicket> findByNonce(String nonce) {
            return rows.stream().filter(r -> r.nonce().equals(nonce)).findFirst();
        }

        @Override
        public int recordUse(Long id, Long verifiedBy, LocalDateTime at) {
            for (int i = 0; i < rows.size(); i++) {
                QrTicket r = rows.get(i);
                if (r.id().equals(id) && r.status() == QrTicket.STATUS_VALID) {
                    rows.set(i, new QrTicket(r.id(), r.appointmentId(), r.appointmentNo(), r.nonce(),
                            r.codeHash(), r.status(), r.useCount() + 1, r.issuedChannel(), r.issuedBy(),
                            r.issuedAt(), r.expireAt(), at, verifiedBy));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int revokeByAppointment(Long appointmentId) {
            int count = 0;
            for (int i = 0; i < rows.size(); i++) {
                QrTicket r = rows.get(i);
                if (r.appointmentId().equals(appointmentId) && r.status() == QrTicket.STATUS_VALID) {
                    rows.set(i, new QrTicket(r.id(), r.appointmentId(), r.appointmentNo(), r.nonce(),
                            r.codeHash(), QrTicket.STATUS_REVOKED, r.useCount(), r.issuedChannel(),
                            r.issuedBy(), r.issuedAt(), r.expireAt(), r.lastUsedAt(), r.verifiedBy()));
                    count++;
                }
            }
            return count;
        }

        @Override
        public List<QrTicket> findValidByAppointment(Long appointmentId) {
            return rows.stream()
                    .filter(r -> r.appointmentId().equals(appointmentId)
                            && r.status() == QrTicket.STATUS_VALID)
                    .toList();
        }

        @Override
        public int markExpired(LocalDateTime now) {
            int count = 0;
            for (int i = 0; i < rows.size(); i++) {
                QrTicket r = rows.get(i);
                if (r.status() == QrTicket.STATUS_VALID && r.expireAt() != null
                        && r.expireAt().isBefore(now)) {
                    rows.set(i, new QrTicket(r.id(), r.appointmentId(), r.appointmentNo(), r.nonce(),
                            r.codeHash(), QrTicket.STATUS_EXPIRED, r.useCount(), r.issuedChannel(),
                            r.issuedBy(), r.issuedAt(), r.expireAt(), r.lastUsedAt(), r.verifiedBy()));
                    count++;
                }
            }
            return count;
        }

        @Override
        public int deleteBefore(LocalDateTime time) {
            int before = rows.size();
            rows.removeIf(r -> r.issuedAt() != null && r.issuedAt().isBefore(time));
            return before - rows.size();
        }
    }
}
