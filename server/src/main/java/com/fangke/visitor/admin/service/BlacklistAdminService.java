package com.fangke.visitor.admin.service;

import com.fangke.visitor.admin.repository.AdminBlacklistRepository;
import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.BlacklistRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.AppointmentService;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.dispatch.service.DispatchService;
import com.fangke.visitor.employee.service.EmployeeValidator;
import com.fangke.visitor.qr.service.QrTicketService;
import com.fangke.visitor.security.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 黑名单管理（docs/02 P-A-05、docs/10 §5）。
 *
 * <p>写入时同时落三件套：{@code target_enc}（AES-GCM 密文）+ {@code target_hmac}
 * （等值查询用，唯一索引）+ {@code target_mask}（列表展示用）；判定侧只查 HMAC，不解密。
 *
 * <p>{@link #enforce} 实现 BR-B-003：拉黑后立即生效——撤销该访客所有在途预约的授权、
 * 把设备名单删掉、作废未使用的通行码，而不是等下次预约才拦。
 */
@Service
public class BlacklistAdminService {

    private static final Logger log = LoggerFactory.getLogger(BlacklistAdminService.class);

    private final AdminBlacklistRepository repository;
    private final CryptoService crypto;
    private final VisitorRepository visitorRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentService appointmentService;
    private final DispatchService dispatchService;
    private final QrTicketService qrTicketService;

    public BlacklistAdminService(AdminBlacklistRepository repository,
                                 CryptoService crypto,
                                 VisitorRepository visitorRepository,
                                 AppointmentRepository appointmentRepository,
                                 AppointmentService appointmentService,
                                 DispatchService dispatchService,
                                 QrTicketService qrTicketService) {
        this.repository = repository;
        this.crypto = crypto;
        this.visitorRepository = visitorRepository;
        this.appointmentRepository = appointmentRepository;
        this.appointmentService = appointmentService;
        this.dispatchService = dispatchService;
        this.qrTicketService = qrTicketService;
    }

    public record Page(List<AdminBlacklistRepository.Row> list, long total) {
    }

    /** 立即生效的结果。 */
    public record EnforceResult(Long visitorId,
                                int appointments,
                                int cancelled,
                                int revoked,
                                int failed,
                                String message) {
    }

    public Page search(String keyword, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<AdminBlacklistRepository.Row> list =
                repository.search(keyword, status, (safePage - 1) * safeSize, safeSize);
        return new Page(list, repository.count(keyword, status));
    }

    @Transactional
    public Long create(Integer targetType, String target, String reason,
                       LocalDateTime validFrom, LocalDateTime validTo, Long operatorId) {
        int type = targetType == null ? BlacklistRepository.TARGET_ID_CARD : targetType;
        if (type != BlacklistRepository.TARGET_ID_CARD && type != BlacklistRepository.TARGET_PHONE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "黑名单对象类型只能是 1（身份证号）或 2（手机号）");
        }
        String plain = type == BlacklistRepository.TARGET_ID_CARD
                ? EmployeeValidator.validateIdCard(EmployeeValidator.requireText(target, "身份证号"))
                : EmployeeValidator.validatePhone(EmployeeValidator.requireText(target, "手机号"));
        if (plain == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "拉黑对象不能为空");
        }
        String masked = type == BlacklistRepository.TARGET_ID_CARD
                ? CryptoService.maskIdCard(plain)
                : CryptoService.maskPhone(plain);

        String text = EmployeeValidator.requireText(reason, "拉黑原因");
        if (text.length() > 255) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "拉黑原因不能超过 255 字");
        }
        if (validFrom != null && validTo != null && validFrom.isAfter(validTo)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "生效开始时间不能晚于结束时间");
        }

        String hmac = crypto.hmac(plain);
        if (repository.existsEffective(type, hmac)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该对象已在黑名单中：" + masked);
        }
        try {
            return repository.insert(type, crypto.encrypt(plain), hmac, masked, text,
                    validFrom, validTo, operatorId);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该对象已在黑名单中：" + masked);
        }
    }

    @Transactional
    public EnforceResult enforce(Long id, Long operatorId) {
        AdminBlacklistRepository.Row row = repository.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.PARAM_INVALID, "黑名单记录不存在：" + id));
        if (row.status() != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该黑名单已失效，无需立即生效");
        }
        String hmac = repository.findTargetHmac(id).orElse(null);
        if (hmac == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "黑名单缺少目标摘要，无法反查访客");
        }

        var visitor = row.targetType() == BlacklistRepository.TARGET_ID_CARD
                ? visitorRepository.findByIdCardHmac(hmac)
                : visitorRepository.findByPhoneHmac(hmac);
        if (visitor.isEmpty()) {
            return new EnforceResult(null, 0, 0, 0, 0,
                    "该对象暂无访客档案，无需撤销在途授权；后续预约会被黑名单直接拦截");
        }

        Long visitorId = visitor.get().getId();
        List<Appointment> active = appointmentRepository.findActiveByVisitor(visitorId);
        int cancelled = 0;
        int revoked = 0;
        int failed = 0;
        for (Appointment appointment : active) {
            try {
                if (appointment.getStatus() == Appointment.PENDING_L1
                        || appointment.getStatus() == Appointment.PENDING_L2) {
                    appointmentService.cancel(appointment.getAppointmentNo(), "命中黑名单，管理员立即生效");
                    cancelled++;
                    continue;
                }
                qrTicketService.revoke(appointment.getAppointmentNo());
                try {
                    dispatchService.revoke(appointment.getId(), operatorId);
                } catch (BusinessException e) {
                    // 已通过但还没生成授权（例如下发前被拉黑）：直接置撤销，避免留下可通行状态
                    if (e.getMessage() != null && e.getMessage().contains("没有授权")) {
                        appointmentRepository.updateStatus(appointment.getId(), Appointment.REVOKED);
                    } else {
                        throw e;
                    }
                }
                revoked++;
            } catch (Exception e) {
                failed++;
                log.warn("拉黑立即生效失败 appointment={}：{}", appointment.getAppointmentNo(), e.getMessage());
            }
        }

        String message = String.format("在途预约 %d 条：撤回 %d，撤销授权 %d，失败 %d",
                active.size(), cancelled, revoked, failed);
        log.info("黑名单立即生效 id={} visitor={} → {}", id, visitorId, message);
        return new EnforceResult(visitorId, active.size(), cancelled, revoked, failed, message);
    }

    @Transactional
    public void updateStatus(Long id, Integer status) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "黑名单 ID 不能为空");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "状态只能是 1（生效）或 0（失效）");
        }
        repository.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.PARAM_INVALID, "黑名单记录不存在：" + id));
        repository.updateStatus(id, status);
    }
}
