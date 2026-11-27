package com.fangke.visitor.appointment.service;

import com.fangke.visitor.appointment.dto.AppointmentCreateRequest;
import com.fangke.visitor.appointment.dto.AppointmentView;
import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.AppointmentMate;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.BlacklistRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.employee.service.EmployeeValidator;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.visit.repository.AreaLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 预约（M4）：访客自约、现场自助登记、员工代邀共用一套校验与落库逻辑。
 */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 访客类型只允许这三种（C-51）。 */
    private static final Set<String> VISITOR_TYPES = Set.of("商务", "施工", "送货");
    /** 最多提前 30 天（BR-P-004）。 */
    private static final int MAX_ADVANCE_DAYS = 30;
    /** 代邀有效期最长 7 天（BR-I-005）。 */
    private static final int INVITE_MAX_DAYS = 7;
    private static final int MAX_MATES = 10;

    private final AppointmentRepository appointmentRepository;
    private final VisitorRepository visitorRepository;
    private final VisitorService visitorService;
    private final VisitEmployeeResolver visitEmployeeResolver;
    private final BlacklistRepository blacklistRepository;
    private final CryptoService crypto;
    private final ApprovalStarter approvalStarter;
    private final GateService gateService;
    private final AppointmentGateService appointmentGateService;
    private final AreaLookup areaLookup;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              VisitorRepository visitorRepository,
                              VisitorService visitorService,
                              VisitEmployeeResolver visitEmployeeResolver,
                              BlacklistRepository blacklistRepository,
                              CryptoService crypto,
                              ApprovalStarter approvalStarter,
                              GateService gateService,
                              AppointmentGateService appointmentGateService,
                              AreaLookup areaLookup) {
        this.appointmentRepository = appointmentRepository;
        this.visitorRepository = visitorRepository;
        this.visitorService = visitorService;
        this.visitEmployeeResolver = visitEmployeeResolver;
        this.blacklistRepository = blacklistRepository;
        this.crypto = crypto;
        this.approvalStarter = approvalStarter;
        this.gateService = gateService;
        this.appointmentGateService = appointmentGateService;
        this.areaLookup = areaLookup;
    }

    /* ---------------- 创建 ---------------- */

    @Transactional
    public AppointmentView create(AppointmentCreateRequest request, int source, String ip, String ua) {
        return doCreate(request, source, null, ip, ua);
    }

    /** 员工代邀：一级自动通过（C-25），有效期上限 7 天。 */
    @Transactional
    public AppointmentView createInvite(AppointmentCreateRequest request, Long employeeId, String ip, String ua) {
        return doCreate(request, Appointment.SOURCE_INVITE, employeeId, ip, ua);
    }

    private AppointmentView doCreate(AppointmentCreateRequest request, int source, Long inviteEmployeeId,
                                     String ip, String ua) {
        Validated v = validate(request, source);
        LocalDateTime start = LocalDateTime.of(v.date, v.startTime);
        LocalDateTime end = LocalDateTime.of(v.date, v.endTime);

        Visitor visitor = visitorService.resolveOrCreate(v.visitorName, v.visitorIdCard, v.visitorPhone,
                request.visitor().company());
        // 微信一键登录：首次预约成功后把 openid 绑到访客档案，下次进小程序直接能看到「我的预约」
        if (request.wxOpenid() != null && !request.wxOpenid().isBlank()) {
            String openid = request.wxOpenid().trim();
            // 列宽 64：mock 模式 openid 是 "mock-" + code，正常不会超，截断只是兜底
            visitorRepository.bindWxOpenid(visitor.getId(), openid.length() > 64 ? openid.substring(0, 64) : openid);
        }
        if (source == Appointment.SOURCE_SELF || source == Appointment.SOURCE_INVITE) {
            visitorService.agree(visitor.getId(), source == Appointment.SOURCE_INVITE ? 2 : 1,
                    v.consentVersion, ip, ua);
        }

        appointmentRepository.findInProgress(visitor.getId()).ifPresent(a -> {
            throw new BusinessException(ErrorCode.APPOINTMENT_DUPLICATED,
                    "该访客已有进行中的预约：" + a.getAppointmentNo());
        });

        Appointment appointment = new Appointment();
        appointment.setAppointmentNo(nextAppointmentNo());
        appointment.setSource(source);
        appointment.setVisitorId(visitor.getId());
        appointment.setVisitEmployeeId(v.visitEmployeeId);
        appointment.setVisitPhoneEnc(v.visitPhone == null ? null : crypto.encrypt(v.visitPhone));
        appointment.setVisitPhoneMask(v.visitPhone == null ? null : CryptoService.maskPhone(v.visitPhone));
        appointment.setVisitorType(v.visitorType);
        appointment.setPurpose(v.purpose);
        appointment.setAreaId(v.areaId);
        appointment.setCompany(v.company);
        appointment.setPosition(v.position);
        appointment.setPeriod(v.period);
        appointment.setPlateNo(v.plateNo);
        appointment.setDepartment(trimToNull(request.department()));
        // 门点：支持多选或「全部门点」（C-64）；「全部」在这里展开成当时的启用门点
        AppointmentGateService.AppliedSelection applied = appointmentGateService.resolveApplication(
                request.gateId(), request.gateIds(), request.gateScope());
        appointment.setGateId(applied.primaryGateId());
        appointment.setGateScope(applied.scope());
        appointment.setStartTime(start);
        appointment.setEndTime(end);
        appointment.setMateMode(Appointment.MATE_TOGETHER);
        // 团体预约：总人数（含负责人）换算成同行人数；同时保留实际录入的同行人数量
        int mateCount = v.mates.size();
        if (request.groupCount() != null && request.groupCount() > 1) {
            mateCount = Math.max(mateCount, request.groupCount() - 1);
        }
        appointment.setMateCount(mateCount);
        // 二维码：所有预约默认开通（不再由访客勾选），审批通过后即可在「我的预约」出示
        appointment.setQrRequested(true);
        appointment.setQrApproved(true);
        appointment.setQrApprovedBy(v.visitEmployeeId);
        appointment.setQrApprovedAt(LocalDateTime.now());
        appointment.setApprovalLevel(1);
        // 代邀：一级自动通过；其余进入待审批（审批任务由 M5 的 ApprovalStarter 创建）
        appointment.setStatus(source == Appointment.SOURCE_INVITE
                ? Appointment.APPROVED : Appointment.PENDING_L1);

        long id = appointmentRepository.insert(appointment);
        appointment.setId(id);
        appointmentGateService.saveApplied(id, applied.gateIds());

        // 主访客 + 同行人都落 appointment_mate，便于下发与核验
        appointmentRepository.insertMate(new AppointmentMate(null, id, visitor.getName(),
                visitor.getIdCardEnc(), visitor.getIdCardHmac(), visitor.getIdCardMask(),
                visitor.getPhoneEnc(), visitor.getPhoneMask(), true, visitor.getPersonNo()));
        for (AppointmentCreateRequest.MateInfo mate : v.mates) {
            String idHmac = mate.idCard() == null ? null : crypto.hmac(mate.idCard());
            appointmentRepository.insertMate(new AppointmentMate(null, id, mate.name(),
                    mate.idCard() == null ? null : crypto.encrypt(mate.idCard()),
                    idHmac,
                    mate.idCard() == null ? null : CryptoService.maskIdCard(mate.idCard()),
                    mate.phone() == null ? null : crypto.encrypt(mate.phone()),
                    mate.phone() == null ? null : CryptoService.maskPhone(mate.phone()),
                    false,
                    // 同行人人员编号由 M6 下发时统一分配（需与主访客编号规则一致）
                    null));
        }

        if (source != Appointment.SOURCE_INVITE) {
            approvalStarter.start(appointment);
        }

        log.info("预约创建 no={} source={} visitor={} 时间={}~{} 随行={} 人",
                appointment.getAppointmentNo(), source, visitor.getIdCardMask(),
                appointment.getStartTime(), appointment.getEndTime(), v.mates.size());

        return detail(appointment.getAppointmentNo());
    }

    /** 现场自助登记（source=3）：日期固定今天，时段默认「现在 → 当天 23:59」。 */
    @Transactional
    public AppointmentView createOnsite(AppointmentCreateRequest request, String ip, String ua) {
        LocalDateTime now = LocalDateTime.now();
        LocalTime startTime = request.startTime() == null
                ? now.toLocalTime().withSecond(0).withNano(0) : request.startTime();
        LocalTime endTime = request.endTime() == null ? LocalTime.of(23, 59) : request.endTime();
        AppointmentCreateRequest normalized = new AppointmentCreateRequest(
                request.visitPhone(), request.department(), request.visitorType(), request.purpose(),
                request.plateNo(), now.toLocalDate(), startTime, endTime,
                request.visitor(), request.mates(), request.consentVersion(),
                request.gateId(), request.gateScope(), request.gateIds(),
                request.company(), request.position(), request.period(), request.groupCount(),
                request.wxOpenid(), request.areaId());
        return doCreate(normalized, Appointment.SOURCE_ONSITE, null, ip, ua);
    }

    /* ---------------- 查询 ---------------- */

    public PageResult<AppointmentView> listByVisitor(Long visitorId, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Appointment> rows = appointmentRepository.findByVisitor(visitorId, status,
                (safePage - 1) * safeSize, safeSize);
        long total = appointmentRepository.countByVisitor(visitorId, status);
        List<AppointmentView> views = rows.stream()
                .map(a -> toView(a, visitorRepository.findById(a.getVisitorId()).orElse(null)))
                .toList();
        return PageResult.of(total, safePage, safeSize, views);
    }

    public AppointmentView detail(String appointmentNo) {
        Appointment a = appointmentRepository.findByNo(appointmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "预约单不存在：" + appointmentNo));
        Visitor visitor = visitorRepository.findById(a.getVisitorId()).orElse(null);
        return toView(a, visitor);
    }

    public Appointment getByNo(String appointmentNo) {
        return appointmentRepository.findByNo(appointmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "预约单不存在：" + appointmentNo));
    }

    /* ---------------- 撤回 ---------------- */

    @Transactional
    public void cancel(String appointmentNo, String reason) {
        Appointment a = getByNo(appointmentNo);
        if (a.getStatus() != Appointment.PENDING_L1 && a.getStatus() != Appointment.PENDING_L2) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "当前状态（" + Appointment.statusText(a.getStatus()) + "）不能撤回");
        }
        a.setStatus(Appointment.WITHDRAWN);
        a.setCancelReason(trimToNull(reason));
        appointmentRepository.update(a);
        approvalStarter.cancel(a);
        log.info("预约撤回 no={} reason={}", appointmentNo, reason);
    }

    /* ---------------- 内部 ---------------- */

    private record Validated(String visitorName, String visitorIdCard, String visitorPhone,
                             String visitPhone, Long visitEmployeeId, String visitorType,
                             String purpose, String company, String position, int period, String plateNo,
                             LocalDate date, LocalTime startTime,
                             LocalTime endTime, List<AppointmentCreateRequest.MateInfo> mates,
                             String consentVersion, Long areaId) {
    }

    private Validated validate(AppointmentCreateRequest request, int source) {
        if (request == null || request.visitor() == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "访客信息不能为空");
        }
        String name = EmployeeValidator.requireText(request.visitor().name(), "访客姓名");
        // 访客只能刷身份证进门（不采集人脸），所以证件号必填——没有证件号就没有通行凭证
        String idCard = EmployeeValidator.validateIdCard(request.visitor().idCard());
        if (idCard == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "访客身份证号必填（到闸机需刷身份证进门）");
        }
        String visitorPhone = EmployeeValidator.validatePhone(request.visitor().phone());
        // C-83：不再由被访人审批，被访人手机/被访人信息改为选填
        String visitPhone = EmployeeValidator.validatePhone(request.visitPhone());

        String visitorType = EmployeeValidator.requireText(request.visitorType(), "访客类型");
        if (!VISITOR_TYPES.contains(visitorType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "访客类型只能是：商务 / 施工 / 送货");
        }
        String purpose = request.purpose();
        if (purpose != null && purpose.length() > 100) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "来访事由最多 100 字");
        }
        String plateNo = request.plateNo();
        if (plateNo != null && plateNo.length() > 16) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "车牌号最多 16 个字符");
        }
        // 统一成相机口径：大写、去空格（否则判定按字符串比对匹配不上）
        plateNo = com.fangke.visitor.common.PlateNo.normalizeToNull(plateNo);

        if (request.startDate() == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "预约日期不能为空");
        }
        // 访客端只选「全天/上午/下午」，由后端换算成时段；老调用（带 start/end）原样使用
        int period = request.period() == null ? 0 : request.period();
        LocalTime startTime = request.startTime();
        LocalTime endTime = request.endTime();
        if (period == Appointment.PERIOD_ALL_DAY || period == Appointment.PERIOD_MORNING
                || period == Appointment.PERIOD_AFTERNOON) {
            switch (period) {
                case Appointment.PERIOD_MORNING -> {
                    startTime = LocalTime.of(0, 0);
                    endTime = LocalTime.of(12, 0);
                }
                case Appointment.PERIOD_AFTERNOON -> {
                    startTime = LocalTime.of(12, 0);
                    endTime = LocalTime.of(23, 59);
                }
                default -> {
                    startTime = LocalTime.of(0, 0);
                    endTime = LocalTime.of(23, 59);
                }
            }
        } else {
            period = Appointment.PERIOD_ALL_DAY;
            if (startTime == null || endTime == null) {
                throw new BusinessException(ErrorCode.PARAM_MISSING, "预约日期与时间不能为空");
            }
        }
        LocalDate today = LocalDate.now();
        LocalDate date = request.startDate();
        if (date.isBefore(today)) {
            throw new BusinessException(ErrorCode.SLOT_EXPIRED, "预约日期不能早于今天");
        }
        if (date.isAfter(today.plusDays(MAX_ADVANCE_DAYS))) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "最多提前 " + MAX_ADVANCE_DAYS + " 天预约");
        }
        if (source == Appointment.SOURCE_INVITE && date.isAfter(today.plusDays(INVITE_MAX_DAYS))) {
            throw new BusinessException(ErrorCode.INVITE_LIMIT_EXCEEDED,
                    "代邀有效期最长 " + INVITE_MAX_DAYS + " 天");
        }
        if (!endTime.isAfter(startTime)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "结束时间必须晚于开始时间（暂不支持跨天预约）");
        }

        // 同行人统一「随行一次放行」：只登记姓名 + 手机号，跟主访客一起进门，不单独下发名单
        List<AppointmentCreateRequest.MateInfo> mates = request.mates() == null ? List.of() : request.mates();
        if (mates.size() > MAX_MATES) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "同行人最多 " + MAX_MATES + " 人");
        }
        List<AppointmentCreateRequest.MateInfo> normalizedMates = new ArrayList<>();
        for (AppointmentCreateRequest.MateInfo mate : mates) {
            String mateName = EmployeeValidator.requireText(mate.name(), "同行人姓名");
            // 同行人跟随主访客通行，不再需要身份证号；填了才校验格式（兼容旧调用）
            String mateIdCard = EmployeeValidator.validateIdCard(mate.idCard());
            EmployeeValidator.validatePhone(mate.phone());
            normalizedMates.add(new AppointmentCreateRequest.MateInfo(mateName, mate.phone(), mateIdCard));
        }

        // 黑名单：证件号（填了才查）与手机号都查（BR-B-002）
        LocalDateTime nowCheck = LocalDateTime.now();
        if ((idCard != null && blacklistRepository.isBlacklisted(
                BlacklistRepository.TARGET_ID_CARD, crypto.hmac(idCard), nowCheck))
                || (visitorPhone != null && blacklistRepository.isBlacklisted(
                BlacklistRepository.TARGET_PHONE, crypto.hmac(visitorPhone), nowCheck))) {
            throw new BusinessException(ErrorCode.BLACKLISTED, "该人员已被列入黑名单，请联系门卫");
        }

        Long visitEmployeeId = visitPhone == null ? null
                : visitEmployeeResolver.resolveByPhoneHmac(crypto.hmac(visitPhone)).orElse(null);

        String consentVersion = request.consentVersion() == null ? "v1.0" : request.consentVersion();
        // 公司/职务：优先取预约单顶层字段，其次取访客信息里的（老调用只传 visitor.company）
        String company = trimToNull(request.company() != null ? request.company()
                : request.visitor().company());
        String position = trimToNull(request.position() != null ? request.position()
                : request.visitor().position());
        if (company != null && company.length() > 128) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "公司名称最多 128 字");
        }
        if (position != null && position.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "职务最多 64 字");
        }
        // 区域（C-75）：可不传（兼容旧版小程序），传了必须是启用中的区域
        Long areaId = request.areaId();
        if (areaId != null && areaLookup.areaName(areaId).isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "来访区域不存在或已停用：" + areaId);
        }
        return new Validated(name, idCard, visitorPhone, visitPhone, visitEmployeeId, visitorType,
                trimToNull(purpose), company, position, period, trimToNull(plateNo), date,
                startTime, endTime, normalizedMates, consentVersion, areaId);
    }

    private AppointmentView toView(Appointment a, Visitor visitor) {
        // 展示实际生效门点（没选门的单据走默认门点），避免前端出现空门点
        Long gateId = a.getGateId() == null ? gateService.defaultGateId() : a.getGateId();
        return AppointmentView.of(a,
                visitor == null ? "" : visitor.getName(),
                visitor == null ? "" : visitor.getIdCardMask(),
                appointmentRepository.findMates(a.getId()),
                gateService.nameOf(gateId),
                appointmentGateService.namesOf(appointmentGateService.appliedGateIds(a)),
                appointmentGateService.namesOf(appointmentGateService.approvedGateIds(a)),
                areaLookup.areaName(a.getAreaId()).orElse(null));
    }

    private String nextAppointmentNo() {
        String prefix = "A" + DAY.format(LocalDate.now());
        for (int attempt = 0; attempt < 3; attempt++) {
            String max = appointmentRepository.findMaxNoByPrefix(prefix).orElse(null);
            int seq = 1;
            if (max != null && max.length() > prefix.length()) {
                try {
                    seq = Integer.parseInt(max.substring(prefix.length())) + 1 + attempt;
                } catch (NumberFormatException ignored) {
                    seq = 1 + attempt;
                }
            }
            String candidate = prefix + String.format("%05d", seq);
            if (appointmentRepository.findByNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        // 极端并发下兜底：用时间戳后缀保证唯一
        return prefix + String.format("%05d", (int) (System.currentTimeMillis() % 100000));
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 供其它模块判断单号是否可被当前状态修改。 */
    public static boolean canWithdraw(int status) {
        return status == Appointment.PENDING_L1 || status == Appointment.PENDING_L2;
    }
}
