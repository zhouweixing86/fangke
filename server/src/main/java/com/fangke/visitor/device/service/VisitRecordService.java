package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.AccessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 签到 / 签离（docs/01 BR-V-009、BR-V-010、BR-V-025、BR-V-026、BR-V-030）。
 *
 * <p>方向完全取设备自身 {@code inout}，平台不按状态推断：
 * 入口设备识别 = 进场（签到），出口设备识别 = 离场（签离）。
 */
@Service
public class VisitRecordService implements VisitRecorder {

    private static final Logger log = LoggerFactory.getLogger(VisitRecordService.class);

    /** 预约单状态：已签到 / 已签离（与 docs/10 §5.1 一致）。 */
    private static final int APPT_CHECKED_IN = 40;
    private static final int APPT_CHECKED_OUT = 50;

    private static final int VISIT_IN_SITE = 1;
    private static final int VISIT_LEFT = 2;
    private static final int VISIT_ABNORMAL_LEFT = 3;
    private static final int CHECKOUT_BY_DEVICE = 1;

    private final VisitRecordRepository repository;

    public VisitRecordService(VisitRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(AccessEvent event, Long eventId) {
        // 失败核验只留痕，不产生签到签离
        if (!event.success() || event.personNo() == null || event.personNo().isBlank()) {
            return;
        }
        if (event.eventTime() == null) {
            return;
        }

        Optional<Long> appointmentId = repository.findAppointmentIdByPersonNo(event.personNo());
        if (appointmentId.isEmpty()) {
            // 员工或未走预约流程的人员：P1 不做到访记录
            log.debug("通行人员无关联预约单，跳签到签离 personNo={}", event.personNo());
            return;
        }
        Long apptId = appointmentId.get();

        if (event.direction() == 1) {
            checkin(apptId, event, eventId);
        } else {
            checkout(apptId, event, eventId);
        }
    }

    private void checkin(Long appointmentId, AccessEvent event, Long eventId) {
        if (repository.findOpenRecordId(appointmentId).isPresent()) {
            log.debug("重复进场，已有在访记录 appointmentId={}", appointmentId);
            return;
        }
        if (repository.findAnyRecordId(appointmentId).isPresent()) {
            // P1：同一预约单只建一条到访记录；二次来访在 M8 扩展
            log.info("该预约单已存在到访记录，跳过重复签到 appointmentId={}", appointmentId);
            return;
        }
        long recordId = repository.insertCheckin(appointmentId, null, event.gateId(),
                event.eventTime(), eventId, event.eventTime().toLocalDate());
        repository.updateAppointmentStatus(appointmentId, APPT_CHECKED_IN);
        log.info("签到成功 appointmentId={} recordId={} at={}", appointmentId, recordId, event.eventTime());
    }

    private void checkout(Long appointmentId, AccessEvent event, Long eventId) {
        Optional<Long> open = repository.findOpenRecordId(appointmentId);
        if (open.isEmpty()) {
            // BR-V-030：出口识别失败/未签到就离场，不阻断人员，但要留异常标记
            log.warn("离场但无在访记录，未生成签离 appointmentId={} personNo={}",
                    appointmentId, event.personNo());
            return;
        }
        int status = event.abnormalFlag() == 1 ? VISIT_ABNORMAL_LEFT : VISIT_LEFT;
        repository.updateCheckout(open.get(), event.eventTime(), eventId, CHECKOUT_BY_DEVICE, status);
        repository.updateAppointmentStatus(appointmentId, APPT_CHECKED_OUT);
        log.info("签离成功 appointmentId={} recordId={} at={} status={}",
                appointmentId, open.get(), event.eventTime(), status);
    }

    /** 供保安工作台手工签离（P2 接口会调用）。 */
    public boolean manualCheckout(Long appointmentId, Long operatorId) {
        Optional<Long> open = repository.findOpenRecordId(appointmentId);
        if (open.isEmpty()) {
            return false;
        }
        repository.updateCheckout(open.get(), java.time.LocalDateTime.now(), null, 2, VISIT_LEFT);
        repository.updateAppointmentStatus(appointmentId, APPT_CHECKED_OUT);
        log.info("保安手工签离 appointmentId={} operator={}", appointmentId, operatorId);
        return true;
    }
}
