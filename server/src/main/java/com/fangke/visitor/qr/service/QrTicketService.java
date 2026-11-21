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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * M7 动态二维码（docs/11 §5.10）。
 *
 * <p>平台侧闭环：<b>签发 → 出示 → 保安核验 → 撤销 → 留痕</b>。
 * 口径按需求文档：60 秒有效（BR-V-019），**60 秒内可重复使用、通行后不作废**（BR-V-020），
 * 校验失败必须留痕并区分原因（BR-V-024）。
 *
 * <p>码格式见 {@link QrCodeVerifier}：{@code RV1.tenant.appointmentNo.exp.nonce.hmac8}；
 * 票据只存码摘要（SHA256）与使用次数，不存明文码。
 * 设备侧扫码识别（{@code /compare/online}）已有独立实现，换新机验证 {@code QR_ONLINE} 能力后再接。
 */
@Service
public class QrTicketService {

    private static final Logger log = LoggerFactory.getLogger(QrTicketService.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 二维码通行在 access_event 里的来源：4 保安核验（1 设备回调 / 2 在线比对 / 3 手工补录）。 */
    public static final int SOURCE_GUARD_QR = 4;
    /** 识别方式：5 二维码。 */
    public static final int IDENTIFY_TYPE_QR = 5;
    /** 票保留天数（docs/10 §12）。 */
    private static final int RETAIN_DAYS = 7;

    public static final String CHANNEL_ADMIN = "ADMIN";
    public static final String CHANNEL_GUARD = "GUARD";
    public static final String CHANNEL_APP = "APP";

    public record IssueResult(String code,
                              String appointmentNo,
                              long expireAtEpoch,
                              String expireAt,
                              int ttlSeconds,
                              String channel,
                              String qrImage) {
    }

    public record VerifyResult(boolean allowed,
                               String reason,
                               String appointmentNo,
                               Long appointmentId,
                               String visitorName,
                               String idCardMask,
                               String visitorType,
                               String purpose,
                               String employeeName,
                               String validFrom,
                               String validTo,
                               int useCount,
                               String ticketStatusText) {

        static VerifyResult reject(String reason) {
            return new VerifyResult(false, reason, null, null, null, null, null, null, null,
                    null, null, 0, "");
        }
    }

    private final QrTicketRepository ticketRepository;
    private final AppointmentRepository appointmentRepository;
    private final VisitorRepository visitorRepository;
    private final EmployeeRepository employeeRepository;
    private final QrCodeVerifier verifier;
    private final GrantChecker grantChecker;
    private final AccessEventRepository accessEventRepository;
    private final VisitRecorder visitRecorder;
    private final AppProperties props;
    private final QrImageService qrImageService;

    public QrTicketService(QrTicketRepository ticketRepository,
                           AppointmentRepository appointmentRepository,
                           VisitorRepository visitorRepository,
                           EmployeeRepository employeeRepository,
                           QrCodeVerifier verifier,
                           GrantChecker grantChecker,
                           AccessEventRepository accessEventRepository,
                           VisitRecorder visitRecorder,
                           AppProperties props,
                           QrImageService qrImageService) {
        this.ticketRepository = ticketRepository;
        this.appointmentRepository = appointmentRepository;
        this.visitorRepository = visitorRepository;
        this.employeeRepository = employeeRepository;
        this.verifier = verifier;
        this.grantChecker = grantChecker;
        this.accessEventRepository = accessEventRepository;
        this.visitRecorder = visitRecorder;
        this.props = props;
        this.qrImageService = qrImageService;
    }

    /** 签发一张 60 秒动态码。只有「已授权且在有效期」的预约能发。 */
    @Transactional
    public IssueResult issue(String appointmentNo, Long operatorId, String channel) {
        Appointment appointment = loadAppointment(appointmentNo);
        // 访客自己取码必须已被审批人同意；保安/管理端是核验通道，不受此限
        if (CHANNEL_APP.equals(channel) && !appointment.isQrApproved()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    appointment.isQrRequested()
                            ? "二维码开门尚未被审批人同意，暂时不能取码"
                            : "该预约没有申请二维码开门，请联系审批人开通");
        }
        Instant now = Instant.now();

        GrantChecker.State state = grantChecker.check(appointmentNo, null, now);
        switch (state) {
            case NO_GRANT -> throw new BusinessException(ErrorCode.GRANT_NOT_READY,
                    "预约单 " + appointmentNo + " 当前没有生效授权，不能生成通行码（状态："
                            + Appointment.statusText(appointment.getStatus()) + "）");
            case OUT_OF_WINDOW -> throw new BusinessException(ErrorCode.NOT_IN_AUTHORIZED_WINDOW);
            default -> {
                // OK / GATE_MISMATCH 都允许：保安台不绑定门点
            }
        }

        int ttl = Math.max(30, props.getQr().getTtlSeconds());
        Instant expireAt = now.plusSeconds(ttl);
        String nonce = randomNonce();
        String signingInput = props.getTenantCode() + "." + appointmentNo + "." + expireAt.getEpochSecond()
                + "." + nonce;
        String code = "RV1." + signingInput + "." + verifier.sign(signingInput);

        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expireLocal = LocalDateTime.ofInstant(expireAt, ZoneId.systemDefault());
        ticketRepository.insert(new QrTicket(null, appointment.getId(), appointmentNo, nonce, sha256(code),
                QrTicket.STATUS_VALID, 0, channel, operatorId, issuedAt, expireLocal, null, null));
        log.info("签发通行码 appointment={} channel={} operator={} expireAt={}",
                appointmentNo, channel, operatorId, DATETIME.format(expireLocal));

        return new IssueResult(code, appointmentNo, expireAt.getEpochSecond(),
                DATETIME.format(expireLocal), ttl, channel, qrImageService.pngBase64(code));
    }

    /**
     * 保安端核验：验签 → 查票 → 授权判定 → **原子消费** → 写通行记录与签到。
     */
    @Transactional
    public VerifyResult verify(String code, Long operatorId, Long gateId) {
        Instant now = Instant.now();
        QrCodeVerifier.Outcome outcome = verifier.verify(code, now);
        switch (outcome.result()) {
            case INVALID_FORMAT, BAD_SIGNATURE -> {
                recordEvent(null, null, "二维码无效", false, gateId, operatorId);
                return VerifyResult.reject("二维码无效或已被篡改");
            }
            case EXPIRED -> {
                recordEvent(outcome.appointmentNo(), null, "二维码已过期", false, gateId, operatorId);
                return VerifyResult.reject("二维码已过期，请让访客刷新后重新出示");
            }
            default -> {
                // 验签通过，继续
            }
        }

        String nonce = extractNonce(code);
        Optional<QrTicket> found = ticketRepository.findByNonce(nonce);
        if (found.isEmpty()) {
            recordEvent(outcome.appointmentNo(), null, "非本平台签发的码", false, gateId, operatorId);
            return VerifyResult.reject("该二维码不是本平台签发的");
        }
        QrTicket ticket = found.get();

        if (ticket.status() == QrTicket.STATUS_REVOKED) {
            recordEvent(ticket.appointmentNo(), null, "二维码已撤销", false, gateId, operatorId);
            return VerifyResult.reject("二维码已撤销");
        }
        if (ticket.status() == QrTicket.STATUS_EXPIRED
                || (ticket.expireAt() != null && ticket.expireAt().isBefore(LocalDateTime.now()))) {
            ticketRepository.markExpired(LocalDateTime.now());
            recordEvent(ticket.appointmentNo(), null, "二维码已过期", false, gateId, operatorId);
            return VerifyResult.reject("二维码已过期，请让访客刷新");
        }

        Long effectiveGate = gateId != null ? gateId : grantChecker.resolveGateId(ticket.appointmentNo());
        GrantChecker.State state = grantChecker.check(ticket.appointmentNo(), effectiveGate, now);
        switch (state) {
            case NO_GRANT -> {
                recordEvent(ticket.appointmentNo(), null, "无有效授权", false, gateId, operatorId);
                return VerifyResult.reject("该预约当前没有有效授权");
            }
            case OUT_OF_WINDOW -> {
                recordEvent(ticket.appointmentNo(), null, "不在授权时段", false, gateId, operatorId);
                return VerifyResult.reject("不在授权时段");
            }
            case GATE_MISMATCH -> {
                recordEvent(ticket.appointmentNo(), null, "门点不符", false, gateId, operatorId);
                return VerifyResult.reject("门点不符");
            }
            default -> {
                // OK
            }
        }

        // BR-V-020：60 秒内可重复使用，这里只累加使用次数，不置失效
        ticketRepository.recordUse(ticket.id(), operatorId, LocalDateTime.now());

        Appointment appointment = appointmentRepository.findById(ticket.appointmentId()).orElse(null);
        String personNo = resolvePersonNo(appointment);
        AccessEvent event = buildEvent(ticket.appointmentNo(), personNo, null, true, effectiveGate);
        Long eventId = null;
        try {
            eventId = accessEventRepository.insertIfAbsent(event).orElse(null);
        } catch (Exception e) {
            log.warn("二维码核验留痕失败 appointment={}：{}", ticket.appointmentNo(), e.getMessage());
        }
        // visit_record.gate_id 非空：拿不到门点时只留通行事件，不写签到（下一次设备回调会补）
        if (eventId != null && appointment != null && effectiveGate != null) {
            visitRecorder.record(event, eventId);
        }
        log.info("二维码核验放行 appointment={} operator={} personNo={}",
                ticket.appointmentNo(), operatorId, personNo);

        return new VerifyResult(true, "核验通过，允许通行", ticket.appointmentNo(),
                ticket.appointmentId(), visitorName(appointment), idCardMask(appointment),
                appointment == null ? null : appointment.getVisitorType(),
                appointment == null ? null : appointment.getPurpose(),
                employeeName(appointment),
                appointment == null || appointment.getStartTime() == null ? null
                        : DATETIME.format(appointment.getStartTime()),
                appointment == null || appointment.getEndTime() == null ? null
                        : DATETIME.format(appointment.getEndTime()),
                ticket.useCount() + 1, QrTicket.statusText(QrTicket.STATUS_VALID));
    }

    /** 撤销某预约下所有未使用票（撤回/驳回/拉黑/手工撤销授权时调用）。 */
    @Transactional
    public int revoke(String appointmentNo) {
        Appointment appointment = loadAppointment(appointmentNo);
        int count = ticketRepository.revokeByAppointment(appointment.getId());
        if (count > 0) {
            log.info("撤销通行码 appointment={} count={}", appointmentNo, count);
        }
        return count;
    }

    public Optional<QrTicket> findLatestValid(String appointmentNo) {
        return appointmentRepository.findByNo(appointmentNo)
                .flatMap(ap -> ticketRepository.findValidByAppointment(ap.getId()).stream().findFirst());
    }

    /** 过期票置灰 + 清理 7 天前的历史票（由 JobScheduler 每日 03:20 调用）。 */
    public int housekeeping() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int expired = ticketRepository.markExpired(now);
            int deleted = ticketRepository.deleteBefore(now.minusDays(RETAIN_DAYS));
            if (expired > 0 || deleted > 0) {
                log.info("二维码票据清理：置过期 {} 条，删除 {} 条", expired, deleted);
            }
            return expired + deleted;
        } catch (Exception e) {
            log.warn("二维码票据清理失败：{}", e.getMessage());
            return 0;
        }
    }

    /* ---------------- 内部 ---------------- */

    private Appointment loadAppointment(String appointmentNo) {
        if (appointmentNo == null || appointmentNo.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "预约单号不能为空");
        }
        return appointmentRepository.findByNo(appointmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "预约单不存在：" + appointmentNo));
    }

    /** 票据只存摘要，不存明文码。 */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("二维码摘要计算失败", e);
        }
    }

    private String randomNonce() {        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(12);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String extractNonce(String code) {
        String[] parts = code == null ? new String[0] : code.trim().split("\\.");
        return parts.length == 6 ? parts[4] : "";
    }

    private String resolvePersonNo(Appointment appointment) {
        if (appointment == null || appointment.getVisitorId() == null) {
            return null;
        }
        return visitorRepository.findById(appointment.getVisitorId())
                .map(Visitor::getPersonNo)
                .orElse(null);
    }

    private String visitorName(Appointment appointment) {
        if (appointment == null || appointment.getVisitorId() == null) {
            return null;
        }
        return visitorRepository.findById(appointment.getVisitorId()).map(Visitor::getName).orElse(null);
    }

    private String idCardMask(Appointment appointment) {
        if (appointment == null || appointment.getVisitorId() == null) {
            return null;
        }
        return visitorRepository.findById(appointment.getVisitorId())
                .map(Visitor::getIdCardMask)
                .orElse(null);
    }

    private String employeeName(Appointment appointment) {
        if (appointment == null || appointment.getVisitEmployeeId() == null) {
            return null;
        }
        return employeeRepository.findById(appointment.getVisitEmployeeId())
                .map(Employee::getName)
                .orElse(null);
    }

    private Long recordEvent(String appointmentNo, String personNo, String failReason,
                             boolean success, Long gateId, Long operatorId) {
        try {
            AccessEvent event = buildEvent(appointmentNo, personNo, failReason, success, gateId);
            return accessEventRepository.insertIfAbsent(event).orElse(null);
        } catch (Exception e) {
            log.warn("二维码核验留痕失败 appointment={}：{}", appointmentNo, e.getMessage());
            return null;
        }
    }

    private AccessEvent buildEvent(String appointmentNo, String personNo, String failReason,
                                   boolean success, Long gateId) {
        String uuid = "Q" + System.currentTimeMillis() + "-" + Math.abs(RANDOM.nextInt(10000));
        String raw = "{\"type\":\"guard_qr\",\"appointmentNo\":"
                + (appointmentNo == null ? "null" : "\"" + appointmentNo + "\"") + "}";
        return new AccessEvent(uuid, null, "GUARD-QR", gateId, 1, LocalDateTime.now(),
                IDENTIFY_TYPE_QR, personNo, null, null, success ? 1 : 0, failReason,
                null, null, false, 0, SOURCE_GUARD_QR, raw);
    }
}
