package com.fangke.visitor.dispatch.service;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.AppointmentRepository;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.appointment.service.AppointmentGateService;
import com.fangke.visitor.approval.service.DispatchTrigger;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.device.model.PersonPayload;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.device.service.PersonStateStore;
import com.fangke.visitor.dispatch.model.AccessGrant;
import com.fangke.visitor.dispatch.model.SyncBatch;
import com.fangke.visitor.dispatch.model.SyncBatchItem;
import com.fangke.visitor.dispatch.repository.AccessGrantRepository;
import com.fangke.visitor.dispatch.repository.SyncBatchRepository;
import com.fangke.visitor.security.CryptoService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 授权下发（M6）：终审通过 → 生成 access_grant → 推送到门点下所有设备 → 跟踪结果。
 *
 * <p>关键设计：
 * <ul>
 *   <li>有效期按自然日整段（C-60）：预约当天 00:00:00 ~ 23:59:59；</li>
 *   <li>推送在<b>独立线程</b>执行：设备是外部依赖，不能拖住审批事务；</li>
 *   <li>每批都落 {@code sync_batch} + 逐设备明细，失败可重试、可追溯；</li>
 *   <li>全部设备成功才把授权置为生效、预约单置为「可通行」。</li>
 * </ul>
 */
@Service
public class DispatchService implements DispatchTrigger {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public record DispatchResult(Long batchId, String batchNo, int total, int success, int fail,
                                 int status, String message) {
    }

    private final AccessGrantRepository grantRepository;
    private final SyncBatchRepository batchRepository;
    private final AppointmentRepository appointmentRepository;
    private final VisitorRepository visitorRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;
    private final PersonStateStore stateStore;
    private final CryptoService crypto;
    private final AppProperties props;
    private final DeviceCapabilityService capabilityService;
    private final GateService gateService;
    private final AppointmentGateService appointmentGateService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "grant-dispatch");
        t.setDaemon(true);
        return t;
    });

    public DispatchService(AccessGrantRepository grantRepository,
                           SyncBatchRepository batchRepository,
                           AppointmentRepository appointmentRepository,
                           VisitorRepository visitorRepository,
                           DeviceRepository deviceRepository,
                           DeviceAdapterFactory adapterFactory,
                           DeviceGateway gateway,
                           PersonStateStore stateStore,
                           CryptoService crypto,
                           AppProperties props,
                           DeviceCapabilityService capabilityService,
                           GateService gateService,
                           AppointmentGateService appointmentGateService) {
        this.grantRepository = grantRepository;
        this.batchRepository = batchRepository;
        this.appointmentRepository = appointmentRepository;
        this.visitorRepository = visitorRepository;
        this.deviceRepository = deviceRepository;
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
        this.stateStore = stateStore;
        this.crypto = crypto;
        this.props = props;
        this.capabilityService = capabilityService;
        this.gateService = gateService;
        this.appointmentGateService = appointmentGateService;
    }

    /** 审批通过时触发：等到审批事务提交后再异步下发。 */
    @Override
    public void trigger(Appointment appointment) {
        if (appointment == null || appointment.getId() == null) {
            return;
        }
        Long appointmentId = appointment.getId();
        String no = appointment.getAppointmentNo();
        Runnable task = () -> {
            try {
                DispatchResult result = dispatch(appointmentId, SyncBatch.TRIGGER_APPROVAL);
                log.info("授权下发完成 appointment={} batch={} total={} success={} fail={} status={}",
                        no, result.batchNo(), result.total(), result.success(), result.fail(), result.status());
            } catch (Exception e) {
                log.error("授权下发失败 appointment={}：{}", no, e.getMessage(), e);
            }
        };
        // 关键：审批仍在事务中时不能立刻下发，否则异步线程读到的预约单还是旧状态
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            executor.submit(task);
                        }
                    });
        } else {
            executor.submit(task);
        }
    }

    /** 同步下发（管理端手动重下 / 重试用）。 */
    public DispatchResult dispatch(Long appointmentId, int triggerType) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "预约单不存在：" + appointmentId));
        if (appointment.getStatus() != Appointment.APPROVED
                && appointment.getStatus() != Appointment.GRANTED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "预约单当前状态（" + Appointment.statusText(appointment.getStatus()) + "）不能下发授权");
        }
        Visitor visitor = visitorRepository.findById(appointment.getVisitorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "访客档案不存在"));

        List<Long> gateIds = appointmentGateService.approvedGateIds(appointment);
        if (gateIds.isEmpty()) {
            log.warn("预约 {} 没有可授权门点，跳过下发", appointment.getAppointmentNo());
            return new DispatchResult(null, null, 0, 0, 0, SyncBatch.STATUS_FAILED, "没有可授权门点");
        }
        // 门点没装设备时降级跳过，不阻塞其他门点（门没装好不该让访客进不来）
        Map<Long, List<Device>> devicesByGate = new LinkedHashMap<>();
        List<Long> emptyGates = new ArrayList<>();
        for (Long gateId : gateIds) {
            List<Device> gateDevices = deviceRepository.findActiveByGate(gateId);
            if (gateDevices.isEmpty()) {
                emptyGates.add(gateId);
            } else {
                devicesByGate.put(gateId, gateDevices);
            }
        }
        int deviceTotal = devicesByGate.values().stream().mapToInt(List::size).sum();
        if (deviceTotal == 0) {
            log.warn("授权门点 {} 下都没有启用设备，跳过下发 appointment={}", gateIds, appointment.getAppointmentNo());
            return new DispatchResult(null, null, 0, 0, 0, SyncBatch.STATUS_FAILED,
                    "授权门点下没有可用设备");
        }

        // 授权按门点建：暂时没设备的门点也建，等设备上线由对账补齐名单
        List<AccessGrant> grants = ensureGrants(appointment, visitor, gateIds);
        String personNo = grants.get(0).personNo();

        String batchNo = "B" + BATCH_NO.format(LocalDateTime.now())
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
        long batchId = batchRepository.insert(new SyncBatch(null, batchNo, triggerType, gateIds.get(0),
                0, 0, 0, SyncBatch.STATUS_RUNNING, null, null));
        batchRepository.start(batchId);

        LocalDate visitDate = appointment.getStartTime().toLocalDate();
        PersonPayload payload = buildPayload(visitor, visitDate);

        int success = 0;
        int fail = 0;
        for (List<Device> gateDevices : devicesByGate.values()) {
            for (Device device : gateDevices) {
                long itemId = batchRepository.addItem(new SyncBatchItem(null, batchId, device.getId(),
                        personNo, SyncBatchItem.ACTION_UPSERT, SyncBatchItem.STATUS_PENDING,
                        null, null, null));
                try {
                    DeviceAdapter adapter = adapterFactory.resolve(device);
                    gateway.execute(device, "upsertPerson:" + payload.getPersonNo(),
                            d -> {
                                capabilityService.require(d, DeviceCapability.PERSON_UPSERT, "下发访客名单");
                                adapter.upsertPerson(d, payload);
                                return null;
                            });
                    batchRepository.updateItem(itemId, SyncBatchItem.STATUS_SUCCESS, null, null);
                    stateStore.save(device.getId(), personNo, PersonStateStore.EXPECT_IN,
                            PersonStateStore.ACTUAL_IN, false);
                    success++;
                } catch (DeviceException e) {
                    batchRepository.updateItem(itemId, SyncBatchItem.STATUS_FAILED,
                            String.valueOf(e.getCode()), truncate(e.getMessage()));
                    stateStore.save(device.getId(), personNo, PersonStateStore.EXPECT_IN,
                            PersonStateStore.ACTUAL_OUT, true);
                    fail++;
                    log.warn("下发失败 device={} person={} code={} msg={}",
                            device.getDeviceSn(), personNo, e.getCode(), e.getMessage());
                } catch (Exception e) {
                    batchRepository.updateItem(itemId, SyncBatchItem.STATUS_FAILED, null, truncate(e.getMessage()));
                    stateStore.save(device.getId(), personNo, PersonStateStore.EXPECT_IN,
                            PersonStateStore.ACTUAL_OUT, true);
                    fail++;
                    log.warn("下发异常 device={} person={}：{}", device.getDeviceSn(), personNo, e.getMessage());
                }
            }
        }

        int status = fail == 0 ? SyncBatch.STATUS_SUCCESS
                : (success == 0 ? SyncBatch.STATUS_FAILED : SyncBatch.STATUS_PARTIAL);
        batchRepository.finish(batchId, deviceTotal, success, fail, status);

        if (fail == 0) {
            grants.forEach(g -> grantRepository.updateStatus(g.id(), AccessGrant.STATUS_ACTIVE));
            appointment.setStatus(Appointment.GRANTED);
            appointment.setGrantedAt(LocalDateTime.now());
            appointmentRepository.update(appointment);
            log.info("授权生效 appointment={} person={} 门点={} 有效期={}~{}",
                    appointment.getAppointmentNo(), personNo, gateIds,
                    grants.get(0).validFrom(), grants.get(0).validTo());
        } else {
            // 部分失败：授权保持待下发，等待重试或对账兜底
            grants.forEach(g -> grantRepository.updateStatus(g.id(), AccessGrant.STATUS_PENDING));
        }

        String message = fail == 0
                ? (emptyGates.isEmpty() ? "下发成功"
                    : "下发成功（门点 " + gateNames(emptyGates) + " 暂无设备，设备上线后由对账补齐）")
                : (success == 0 ? "所有设备下发失败" : "部分设备下发失败，稍后自动对账重试");
        return new DispatchResult(batchId, batchNo, deviceTotal, success, fail, status, message);
    }

    /** 撤销授权：先从各门点设备删除，再置授权与预约单状态（失败也让本地状态先落地）。 */
    public DispatchResult revoke(Long appointmentId, Long operatorId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "预约单不存在：" + appointmentId));
        List<AccessGrant> grants = grantRepository.findAllByAppointmentId(appointmentId);
        if (grants.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该预约单没有授权记录");
        }
        String personNo = grants.get(0).personNo();
        List<Long> gateIds = grants.stream().map(AccessGrant::gateId)
                .filter(g -> g != null).distinct().toList();
        Long primaryGate = gateIds.isEmpty() ? gateService.defaultGateId() : gateIds.get(0);

        String batchNo = "B" + BATCH_NO.format(LocalDateTime.now())
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
        long batchId = batchRepository.insert(new SyncBatch(null, batchNo, SyncBatch.TRIGGER_MANUAL, primaryGate,
                0, 0, 0, SyncBatch.STATUS_RUNNING, null, null));
        batchRepository.start(batchId);

        int success = 0;
        int fail = 0;
        int deviceTotal = 0;
        for (Long gateId : gateIds) {
            List<Device> devices = deviceRepository.findActiveByGate(gateId);
            deviceTotal += devices.size();
            for (Device device : devices) {
                long itemId = batchRepository.addItem(new SyncBatchItem(null, batchId, device.getId(),
                        personNo, SyncBatchItem.ACTION_REMOVE, SyncBatchItem.STATUS_PENDING,
                        null, null, null));
                try {
                    DeviceAdapter adapter = adapterFactory.resolve(device);
                    gateway.execute(device, "removePerson:" + personNo, d -> {
                        capabilityService.require(d, DeviceCapability.PERSON_REMOVE, "撤销访客名单");
                        adapter.removePerson(d, personNo, props.getDevice().getVisitorUserType());
                        return null;
                    });
                    batchRepository.updateItem(itemId, SyncBatchItem.STATUS_SUCCESS, null, null);
                    stateStore.save(device.getId(), personNo, PersonStateStore.EXPECT_OUT,
                            PersonStateStore.ACTUAL_OUT, false);
                    success++;
                } catch (Exception e) {
                    batchRepository.updateItem(itemId, SyncBatchItem.STATUS_FAILED, null, truncate(e.getMessage()));
                    fail++;
                    log.warn("撤销时设备删除失败 device={} person={}：{}",
                            device.getDeviceSn(), personNo, e.getMessage());
                }
            }
        }
        int status = fail == 0 ? SyncBatch.STATUS_SUCCESS
                : (success == 0 ? SyncBatch.STATUS_FAILED : SyncBatch.STATUS_PARTIAL);
        batchRepository.finish(batchId, deviceTotal, success, fail, status);

        grants.forEach(g -> grantRepository.revoke(g.id(), operatorId));
        appointment.setStatus(Appointment.REVOKED);
        appointmentRepository.update(appointment);
        log.info("授权撤销 appointment={} person={} 门点={} batch={}", appointment.getAppointmentNo(),
                personNo, gateIds, batchNo);
        return new DispatchResult(batchId, batchNo, deviceTotal, success, fail, status, "授权已撤销");
    }

    /* ---------------- 批次重试 ---------------- */

    public record RetryResult(Long batchId, int appointments, int success, int fail) {
    }

    /**
     * 重试某个批次里失败的人员：按 personNo 反查授权 → 重新走一次「手动下发」。
     *
     * <p>不直接重放原报文：下发内容包含有效期与证件号，必须按当前数据重建，
     * 否则重试会用旧数据覆盖设备名单。
     */
    public RetryResult retryBatch(Long batchId) {
        List<SyncBatchItem> items = batchRepository.items(batchId, 0, 500);
        java.util.LinkedHashSet<Long> appointmentIds = new java.util.LinkedHashSet<>();
        for (SyncBatchItem item : items) {
            if (item.status() != SyncBatchItem.STATUS_FAILED) {
                continue;
            }
            grantRepository.findLatestByPersonNo(item.personNo())
                    .map(AccessGrant::appointmentId)
                    .filter(java.util.Objects::nonNull)
                    .ifPresent(appointmentIds::add);
        }

        int success = 0;
        int fail = 0;
        for (Long appointmentId : appointmentIds) {
            try {
                DispatchResult result = dispatch(appointmentId, SyncBatch.TRIGGER_MANUAL);
                success += result.success();
                fail += result.fail();
            } catch (Exception e) {
                fail++;
                log.warn("批次重试失败 batch={} appointment={}：{}", batchId, appointmentId, e.getMessage());
            }
        }
        log.info("批次重试 batch={} 涉及预约 {} 条，成功 {}，失败 {}", batchId, appointmentIds.size(), success, fail);
        return new RetryResult(batchId, appointmentIds.size(), success, fail);
    }

    /* ---------------- 清理过期名单（JOB-05） ---------------- */

    /**
     * 删除已过期超过 7 天的授权名单（设备端清理），并把授权置为已过期。
     *
     * @return 处理的授权条数
     */
    public int cleanupExpired() {
        List<AccessGrant> expired = grantRepository.findExpiredBefore(LocalDateTime.now().minusDays(7), 200);
        int cleaned = 0;
        for (AccessGrant grant : expired) {
            List<Device> devices = deviceRepository.findActiveByGate(grant.gateId());
            for (Device device : devices) {
                try {
                    DeviceAdapter adapter = adapterFactory.resolve(device);
                    gateway.execute(device, "removeExpired:" + grant.personNo(), d -> {
                        capabilityService.require(d, DeviceCapability.PERSON_REMOVE, "清理过期名单");
                        adapter.removePerson(d, grant.personNo(), props.getDevice().getVisitorUserType());
                        return null;
                    });
                } catch (Exception e) {
                    log.warn("清理过期名单失败 device={} person={}：{}",
                            device.getDeviceSn(), grant.personNo(), e.getMessage());
                }
            }
            grantRepository.updateStatus(grant.id(), AccessGrant.STATUS_EXPIRED);
            cleaned++;
        }
        if (cleaned > 0) {
            log.info("过期名单清理完成，共 {} 条", cleaned);
        }
        return cleaned;
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 按批准门点建授权：每个门点一行（C-64）。
     *
     * <p>幂等：已存在同门点授权时复用，只补新增的门点；审批人「加门点」后重推能增量生效。
     */
    private List<AccessGrant> ensureGrants(Appointment appointment, Visitor visitor, List<Long> gateIds) {
        LocalDate visitDate = appointment.getStartTime().toLocalDate();
        List<AccessGrant> grants = new ArrayList<>(gateIds.size());
        for (Long gateId : gateIds) {
            Optional<AccessGrant> existing = grantRepository.findByAppointmentIdAndGate(
                    appointment.getId(), gateId);
            if (existing.isPresent()) {
                AccessGrant grant = existing.get();
                if (grant.status() == AccessGrant.STATUS_ACTIVE) {
                    // 已生效：仍然重推一次（幂等覆盖），但不重新建授权
                    log.debug("授权已存在且生效，重推 appointment={} gate={}",
                            appointment.getAppointmentNo(), gateId);
                }
                grants.add(grant);
                continue;
            }
            AccessGrant grant = new AccessGrant(null,
                    AccessGrant.SUBJECT_VISITOR,
                    visitor.getId(),
                    visitor.getPersonNo(),
                    appointment.getId(),
                    gateId,
                    LocalDateTime.of(visitDate, LocalTime.MIN),
                    LocalDateTime.of(visitDate, LocalTime.of(23, 59, 59)),
                    AccessGrant.STATUS_PENDING);
            long id = grantRepository.insert(grant);
            grants.add(new AccessGrant(id, grant.subjectType(), grant.subjectId(), grant.personNo(),
                    grant.appointmentId(), grant.gateId(), grant.validFrom(), grant.validTo(),
                    AccessGrant.STATUS_DISPATCHING));
        }
        return grants;
    }

    private String gateNames(List<Long> gateIds) {
        return gateIds.stream().map(gateService::nameOf).filter(n -> !n.isEmpty())
                .reduce((a, b) -> a + "、" + b).orElse("");
    }

    private PersonPayload buildPayload(Visitor visitor, LocalDate visitDate) {
        PersonPayload payload = new PersonPayload();
        payload.setPersonNo(visitor.getPersonNo());
        payload.setName(visitor.getName());
        payload.setIdCard(crypto.decrypt(visitor.getIdCardEnc()));
        payload.setUserType(props.getDevice().getVisitorUserType());
        payload.setValidFrom(visitDate);
        payload.setValidTo(visitDate);
        payload.setTimeGroupId(0);
        return payload;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
