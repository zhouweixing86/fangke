package com.fangke.visitor.employee.service;

import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.dispatch.model.AccessGrant;
import com.fangke.visitor.dispatch.repository.AccessGrantRepository;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.model.EmployeeFace;
import com.fangke.visitor.employee.repository.EmployeeFaceRepository;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fangke.visitor.device.service.PersonStateStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 员工名单下发（C-66）：员工入职即下发到所有启用门点，刷脸/刷证才能进厂区。
 *
 * <p>做法：给每个启用门点建一条 {@code access_grant(subject_type=1)}，
 * 再把人员推到该门点下的设备。门点没有设备时也建授权——装机后由对账
 * （{@code JdbcExpectedPersonPort#expectedOn}）自动补齐名单。
 *
 * <p>员工授权**长期有效**：有效期取员工档案的入职/离职日期，没填则到 9999-12-31。
 */
@Service
public class EmployeeDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeDispatchService.class);
    private static final LocalDate FOREVER = LocalDate.of(9999, 12, 31);

    public record SyncResult(int employees, int grants, int devices, int success, int fail, String message) {
    }

    private final EmployeeRepository employeeRepository;
    private final EmployeeFaceRepository faceRepository;
    private final StorageService storageService;
    private final DeviceRepository deviceRepository;
    private final AccessGrantRepository grantRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;
    private final DeviceCapabilityService capabilityService;
    private final GateService gateService;
    private final CryptoService crypto;
    private final PersonStateStore stateStore;

    public EmployeeDispatchService(EmployeeRepository employeeRepository,
                                   EmployeeFaceRepository faceRepository,
                                   StorageService storageService,
                                   DeviceRepository deviceRepository,
                                   AccessGrantRepository grantRepository,
                                   DeviceAdapterFactory adapterFactory,
                                   DeviceGateway gateway,
                                   DeviceCapabilityService capabilityService,
                                   GateService gateService,
                                   CryptoService crypto,
                                   PersonStateStore stateStore) {
        this.employeeRepository = employeeRepository;
        this.faceRepository = faceRepository;
        this.storageService = storageService;
        this.deviceRepository = deviceRepository;
        this.grantRepository = grantRepository;
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
        this.capabilityService = capabilityService;
        this.gateService = gateService;
        this.crypto = crypto;
        this.stateStore = stateStore;
    }

    /* ---------------- 对外 ---------------- */

    /** 员工档案变更后的静默同步：失败只记日志，不能让"保存员工"失败。 */
    public void syncQuietly(Employee employee) {
        try {
            sync(employee);
        } catch (Exception e) {
            log.warn("员工名单同步失败 empNo={}：{}", employee == null ? null : employee.getEmpNo(), e.getMessage());
        }
    }

    /** 建/更新该员工在所有启用门点的授权，并推送到这些门点的设备。 */
    @Transactional
    public SyncResult sync(Employee employee) {
        if (employee == null || employee.getId() == null) {
            return new SyncResult(0, 0, 0, 0, 0, "员工不存在");
        }
        List<Gate> gates = assignableGates();
        if (gates.isEmpty()) {
            return new SyncResult(1, 0, 0, 0, 0, "没有可用门点");
        }
        boolean active = employee.getStatus() == Employee.STATUS_ACTIVE;
        for (Gate gate : gates) {
            ensureGrant(employee, gate.id(), active);
        }
        if (!active) {
            return new SyncResult(1, gates.size(), 0, 0, 0, "员工不在岗，已撤销授权");
        }

        PersonPayload payload = payload(employee);
        int devices = 0;
        int success = 0;
        int fail = 0;
        for (Gate gate : gates) {
            for (Device device : DeviceRepository.personDevices(deviceRepository.findActiveByGate(gate.id()))) {
                devices++;
                try {
                    push(device, payload);
                    success++;
                } catch (Exception e) {
                    fail++;
                    log.warn("员工下发失败 device={} person={}：{}",
                            device.getDeviceSn(), employee.getPersonNo(), e.getMessage());
                }
            }
        }
        String message = fail == 0 ? "已下发" : "部分设备下发失败，稍后由对账补齐";
        return new SyncResult(1, gates.size(), devices, success, fail, message);
    }

    /** 停用/离职：撤销全部授权并从设备删除。 */
    @Transactional
    public SyncResult revoke(Employee employee, Long operatorId) {
        if (employee == null || employee.getId() == null) {
            return new SyncResult(0, 0, 0, 0, 0, "员工不存在");
        }
        List<AccessGrant> grants = grantRepository.findBySubject(
                AccessGrant.SUBJECT_EMPLOYEE, employee.getId());
        int devices = 0;
        int success = 0;
        int fail = 0;
        for (AccessGrant grant : grants) {
            for (Device device : DeviceRepository.personDevices(deviceRepository.findActiveByGate(grant.gateId()))) {
                devices++;
                try {
                    remove(device, employee.getPersonNo());
                    success++;
                } catch (Exception e) {
                    fail++;
                    log.warn("员工删除失败 device={} person={}：{}",
                            device.getDeviceSn(), employee.getPersonNo(), e.getMessage());
                }
            }
            grantRepository.revoke(grant.id(), operatorId);
        }
        return new SyncResult(1, grants.size(), devices, success, fail, "已撤销员工通行");
    }

    /** 一键全量：在岗员工全部下发；停用员工全部撤销。 */
    @Transactional
    public SyncResult syncAll() {
        List<Employee> active = employeeRepository.search(null, Employee.STATUS_ACTIVE, 0, 10000);
        List<Employee> disabled = employeeRepository.search(null, Employee.STATUS_DISABLED, 0, 10000);

        int grants = 0;
        int devices = 0;
        int success = 0;
        int fail = 0;
        for (Employee employee : active) {
            SyncResult r = sync(employee);
            grants += r.grants();
            devices += r.devices();
            success += r.success();
            fail += r.fail();
        }
        for (Employee employee : disabled) {
            SyncResult r = revoke(employee, null);
            grants += r.grants();
            devices += r.devices();
        }
        String message = "在岗 " + active.size() + " 人，停用清理 " + disabled.size() + " 人";
        log.info("员工全量同步完成 {}", message);
        return new SyncResult(active.size() + disabled.size(), grants, devices, success, fail, message);
    }

    /* ---------------- 内部 ---------------- */

    /** 可授权门点：启用且不是「待分配」。 */
    private List<Gate> assignableGates() {
        return gateService.listEnabled().stream().filter(g -> !g.unassigned()).toList();
    }

    private AccessGrant ensureGrant(Employee employee, Long gateId, boolean active) {
        LocalDateTime from = employee.getValidFrom() == null
                ? LocalDate.now().atStartOfDay()
                : employee.getValidFrom().atStartOfDay();
        LocalDateTime to = employee.getValidTo() == null
                ? FOREVER.atTime(23, 59, 59)
                : employee.getValidTo().atTime(23, 59, 59);

        Optional<AccessGrant> existing = grantRepository.findBySubjectAndGate(
                AccessGrant.SUBJECT_EMPLOYEE, employee.getId(), gateId);
        if (existing.isPresent()) {
            AccessGrant grant = existing.get();
            int wanted = active ? AccessGrant.STATUS_ACTIVE : AccessGrant.STATUS_REVOKED;
            if (grant.status() != wanted) {
                grantRepository.updateStatus(grant.id(), wanted);
            }
            return grant;
        }
        AccessGrant grant = new AccessGrant(null,
                AccessGrant.SUBJECT_EMPLOYEE,
                employee.getId(),
                employee.getPersonNo(),
                null,
                gateId,
                from,
                to,
                active ? AccessGrant.STATUS_ACTIVE : AccessGrant.STATUS_REVOKED);
        long id = grantRepository.insert(grant);
        return new AccessGrant(id, grant.subjectType(), grant.subjectId(), grant.personNo(),
                null, grant.gateId(), grant.validFrom(), grant.validTo(), grant.status());
    }

    private void push(Device device, PersonPayload payload) {
        DeviceAdapter adapter = adapterFactory.resolve(device);
        gateway.execute(device, "upsertEmployee:" + payload.getPersonNo(), d -> {
            capabilityService.require(d, DeviceCapability.PERSON_UPSERT, "下发员工名单");
            adapter.upsertPerson(d, payload);
            return null;
        });
        // 记一笔对账状态：设备上「该有这个人」，残留名单清理据此算差集
        stateStore.save(device.getId(), payload.getPersonNo(), PersonStateStore.EXPECT_IN,
                PersonStateStore.ACTUAL_IN, false);
    }

    private void remove(Device device, String personNo) {
        DeviceAdapter adapter = adapterFactory.resolve(device);
        gateway.execute(device, "removeEmployee:" + personNo, d -> {
            capabilityService.require(d, DeviceCapability.PERSON_REMOVE, "删除员工名单");
            adapter.removePerson(d, personNo, PersonPayload.USER_TYPE_WHITE);
            return null;
        });
        stateStore.save(device.getId(), personNo, PersonStateStore.EXPECT_OUT,
                PersonStateStore.ACTUAL_OUT, false);
    }

    private PersonPayload payload(Employee employee) {
        PersonPayload payload = new PersonPayload();
        payload.setPersonNo(employee.getPersonNo());
        payload.setName(employee.getName());
        payload.setIdCard(employee.getIdCardEnc() == null ? null : crypto.decrypt(employee.getIdCardEnc()));
        payload.setUserType(PersonPayload.USER_TYPE_WHITE);
        payload.setValidFrom(employee.getValidFrom() == null ? LocalDate.now() : employee.getValidFrom());
        payload.setValidTo(employee.getValidTo() == null ? FOREVER : employee.getValidTo());
        payload.setPhotoBase64(facePhoto(employee.getId()));
        return payload;
    }

    /** 员工人脸照片 → base64（优先压缩件）；失败返回 null 由 passAlgo 兜底。 */
    private String facePhoto(Long employeeId) {
        if (employeeId == null || !storageService.configured()) {
            return null;
        }
        try {
            return faceRepository.findCurrent(employeeId)
                    .map(face -> {
                        String key = face.ossKeyStd() != null && !face.ossKeyStd().isBlank()
                                ? face.ossKeyStd() : face.ossKey();
                        return key == null ? null : Base64.getEncoder().encodeToString(storageService.getObject(key));
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.warn("读取员工 {} 照片失败，本次按无照片下发：{}", employeeId, e.getMessage());
            return null;
        }
    }
}
