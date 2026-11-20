package com.fangke.visitor.device.pull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.AccessEvent;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.service.AccessEventRepository;
import com.fangke.visitor.device.service.CaptureUploader;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.device.service.VisitRecorder;
import com.fangke.visitor.dispatch.model.AccessGrant;
import com.fangke.visitor.dispatch.repository.AccessGrantRepository;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.model.EmployeeFace;
import com.fangke.visitor.employee.repository.EmployeeFaceRepository;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.gate.service.GateService;
import com.fangke.visitor.qr.service.QrTicketService;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 轮询式终端（HTTP 客户端版）的业务实现。
 *
 * <p>与九鼎设备的根本差异：<b>平台不能主动连设备</b>。所以：
 * <ul>
 *   <li>下发名单 → 入 {@code device_cmd} 队列；设备心跳时拿到 {@code action}，再回来拉数据；</li>
 *   <li>远程开门 → 入队，最快一个心跳周期生效（{@code fangke.device.pull-heart-seconds}）；</li>
 *   <li>通行记录 / 二维码 → 设备主动 POST 给我们，是实时的；</li>
 *   <li>设备端名单总数、参数、版本 → 平台拿不到（协议里没有"平台问设备"的接口）。</li>
 * </ul>
 * 详见 docs/14。
 */
@Service
public class OpenApiDeviceService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiDeviceService.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单次交付给设备的名单条数上限（避免响应体过大）。 */
    private static final int MAX_BATCH = 100;

    private final DeviceRepository deviceRepository;
    private final PullCommandRepository commandRepository;
    private final AccessGrantRepository grantRepository;
    private final VisitorRepository visitorRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeFaceRepository faceRepository;
    private final StorageService storageService;
    private final CryptoService crypto;
    private final AccessEventRepository accessEventRepository;
    private final VisitRecorder visitRecorder;
    private final CaptureUploader captureUploader;
    private final QrTicketService qrTicketService;
    private final GateService gateService;
    private final AppProperties props;

    public OpenApiDeviceService(DeviceRepository deviceRepository,
                                PullCommandRepository commandRepository,
                                AccessGrantRepository grantRepository,
                                VisitorRepository visitorRepository,
                                EmployeeRepository employeeRepository,
                                EmployeeFaceRepository faceRepository,
                                StorageService storageService,
                                CryptoService crypto,
                                AccessEventRepository accessEventRepository,
                                VisitRecorder visitRecorder,
                                CaptureUploader captureUploader,
                                QrTicketService qrTicketService,
                                GateService gateService,
                                AppProperties props) {
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.grantRepository = grantRepository;
        this.visitorRepository = visitorRepository;
        this.employeeRepository = employeeRepository;
        this.faceRepository = faceRepository;
        this.storageService = storageService;
        this.crypto = crypto;
        this.accessEventRepository = accessEventRepository;
        this.visitRecorder = visitRecorder;
        this.captureUploader = captureUploader;
        this.qrTicketService = qrTicketService;
        this.gateService = gateService;
        this.props = props;
    }

    /* ---------------- 设备解析 ---------------- */

    /**
     * 按设备号找设备；首次出现自动登记（channel=PULL），避免现场还要手工建档。
     */
    public Device resolveOrRegister(String deviceNo, String ip) {
        if (deviceNo == null || deviceNo.isBlank()) {
            return null;
        }
        Optional<Device> existing = deviceRepository.findBySn(deviceNo.trim());
        if (existing.isPresent()) {
            return existing.get();
        }
        Device device = new Device();
        device.setDeviceSn(deviceNo.trim());
        // mac 有唯一索引：用 SN 生成占位，等设备上报真实 mac 再更新
        device.setMac("PULL-" + deviceNo.trim());
        device.setDeviceName("新终端-" + deviceNo.trim());
        device.setChannel(Device.Channel.PULL);
        // 先进「待分配」门点：新设备落到谁家还不确定，误进 1 号门会导致名单下发错门。
        device.setGateId(gateService.unassignedGateId());
        device.setDirection(1);
        device.setIp(ip);
        device.setPort(8091);
        device.setStatus(1);
        long id = deviceRepository.register(device);
        device.setId(id);
        log.warn("自动登记轮询式终端 deviceNo={} ip={}（已放入「待分配」，请在设备管理里分配门点并补 mac/型号）",
                deviceNo, ip);
        return device;
    }

    public void touchHeartbeat(Device device, String ip) {
        deviceRepository.updateHeartbeat(device.getDeviceSn(), ip, null, LocalDateTime.now(), null, null);
    }

    /* ---------------- 心跳：决定下发哪个 action ---------------- */

    /**
     * @return 心跳响应 data：{@code {action, actionPara, heartTime}}；无事可做时 action 为 {@code "null"}
     */
    public Map<String, Object> heartbeat(Device device) {
        int heartSeconds = Math.max(5, props.getDevice().getPullHeartSeconds());
        requeueStale(device);

        String action = "null";
        Object actionPara = null;

        if (commandRepository.hasPending(device.getId(), PullCommandRepository.TYPE_OPEN_DOOR)) {
            action = "setDeviceRemoteOpen";
            // 开门没有回执接口：随心跳下发即视为完成，避免超时重排导致反复开门
            completeSimpleCommand(device.getId(), PullCommandRepository.TYPE_OPEN_DOOR,
                    "已随心跳下发，设备自行执行");
        } else if (commandRepository.hasPending(device.getId(), PullCommandRepository.TYPE_SYNC_TIME)) {
            action = "syncDeviceTime";
            claim(device, PullCommandRepository.TYPE_SYNC_TIME);
        } else if (commandRepository.hasPending(device.getId(), PullCommandRepository.TYPE_PARAM_SYNC)) {
            action = "syncDeviceParameter";
            claim(device, PullCommandRepository.TYPE_PARAM_SYNC);
        } else if (commandRepository.hasPending(device.getId(), PullCommandRepository.TYPE_ADD_PERSON)) {
            action = "addDevicWhiteInfo";
            // 不在这里 claim：设备心跳后会立刻调用 addDevicWhiteInfo 取列表，
            // 若心跳先把指令置为"执行中"，设备取到的就是空列表（2026-11-20 修复）
        } else if (commandRepository.hasPending(device.getId(), PullCommandRepository.TYPE_UPDATE_PERSON)) {
            action = "updateDeviceWhiteInfo";
        } else if (commandRepository.hasPending(device.getId(), PullCommandRepository.TYPE_DEL_PERSON)) {
            action = "delDevicWhiteList";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("heartTime", heartSeconds);
        data.put("action", action);
        data.put("actionPara", actionPara);
        if (!"null".equals(action)) {
            log.info("心跳下发指令 device={} action={} 队列剩余={}",
                    device.getDeviceSn(), action, commandRepository.countPending(device.getId()));
        }
        return data;
    }

    private void requeueStale(Device device) {
        int timeout = Math.max(30, props.getDevice().getPullCommandTimeoutSeconds());
        int affected = commandRepository.requeueStale(device.getId(),
                LocalDateTime.now().minusSeconds(timeout), Math.max(1, props.getDevice().getMaxRetry()));
        if (affected > 0) {
            log.info("设备 {} 有 {} 条指令超时未回执，已重新排队/置失败", device.getDeviceSn(), affected);
        }
    }

    private void claim(Device device, String cmdType) {
        List<PullCommandRepository.Row> rows = commandRepository.listPending(device.getId(), cmdType, MAX_BATCH);
        if (rows.isEmpty()) {
            return;
        }
        commandRepository.markDispatching(rows.stream().map(PullCommandRepository.Row::id).toList(),
                LocalDateTime.now());
    }

    /* ---------------- 名单同步 ---------------- */

    /** getAllDeviceWhiteList：平台侧全部有效名单索引（idno + personVer）。 */
    public List<Map<String, Object>> personIndex(Device device) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AccessGrant grant : activeGrants(device)) {
            PersonView view = build(grant);
            if (view == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("idno", view.idno());
            item.put("personVer", view.personVer());
            list.add(item);
        }
        return list;
    }

    /**
     * addDevicWhiteInfo / updateDeviceWhiteInfo / delDevicWhiteList：
     * 把队列里待执行的名单变化交给设备，并标记「执行中」。
     */
    public List<Map<String, Object>> claimPersonChanges(Device device, String cmdType) {
        List<PullCommandRepository.Row> rows = commandRepository.listPending(device.getId(), cmdType, MAX_BATCH);
        List<Map<String, Object>> list = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (PullCommandRepository.Row row : rows) {
            String personNo = personNoOf(row.payload());
            if (personNo == null) {
                continue;
            }
            PersonView view = build(personNo, device);
            // 删除指令下发时授权已经撤销，build() 必然返回 null；此时必须回退到档案里查证件号，
            // 否则拿 personNo（如 E000001）当 idno 告诉设备删人，设备找不到这条 → 人删不掉
            String idno = view == null ? idnoOfPerson(personNo) : view.idno();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("idno", idno);
            if (!PullCommandRepository.TYPE_DEL_PERSON.equals(cmdType)) {
                item.put("personVer", view == null ? 1 : view.personVer());
            }
            list.add(item);
            ids.add(row.id());
        }
        if (!ids.isEmpty()) {
            commandRepository.markDispatching(ids, LocalDateTime.now());
            log.info("交付名单变更 device={} type={} 条数={}", device.getDeviceSn(), cmdType, list.size());
        }
        return list;
    }

    /** getDevicWhiteInfo：单个人员详情（含照片 base64）。 */
    public Map<String, Object> personDetail(Device device, String idno) {
        PersonView view = findByIdno(device, idno);
        if (view == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("usertype", "white");
        data.put("name", view.name());
        data.put("idno", view.idno());
        data.put("peoplestartdate", DATETIME.format(deviceValidFrom(view.validFrom())));
        data.put("peopleenddate", DATETIME.format(deviceValidTo(view.validTo())));
        data.put("timegroupid", 0);
        data.put("group", "访客系统");
        data.put("groupSuffix", view.subjectType() == AccessGrant.SUBJECT_EMPLOYEE ? "员工" : "访客");
        // 访客没有照片：passAlgo=true 表示该名单不需要图片
        data.put("passAlgo", view.photoBase64() == null);
        if (view.photoBase64() != null) {
            data.put("picData1", view.photoBase64());
        }
        // 有证件号时顺带回传身份证有效期字段，设备不强制
        return data;
    }

    /** syncWhiteResult：设备回报新增/修改/删除结果，按人完成队列指令。 */
    public void syncWhiteResult(Device device, String idno, Integer resultType, Integer resultCode, String msg) {
        int status = resultCode != null && resultCode == 0 ? 2 : 3;
        String cmdType;
        if (resultType == null) {
            cmdType = PullCommandRepository.TYPE_ADD_PERSON;
        } else {
            cmdType = switch (resultType) {
                case 2 -> PullCommandRepository.TYPE_DEL_PERSON;
                case 1 -> PullCommandRepository.TYPE_UPDATE_PERSON;
                default -> PullCommandRepository.TYPE_ADD_PERSON;
            };
        }
        PersonView view = findByIdno(device, idno);
        String personNo = view == null ? idno : view.personNo();
        int affected = commandRepository.markDoneByPerson(device.getId(), cmdType, personNo, status, msg);
        log.info("名单回执 device={} idno={} type={} code={} msg={} 更新队列 {} 条",
                device.getDeviceSn(), idno, resultType, resultCode, msg, affected);
    }

    /* ---------------- 记录推送 ---------------- */

    /** uploadDetectRecord：记录落库 → 异步传照片 → 触发签到/签离。 */
    public Long receiveRecord(Device device, JsonNode data, String rawJson) {
        String idCard = text(data, "idNum");
        String name = text(data, "name");
        LocalDateTime time = OpenApiRecordMapper.eventTime(text(data, "time"));
        Integer openType = number(data, "openType");
        Integer inout = number(data, "inout");
        Integer resultStatus = number(data, "resultStatus");
        String temperature = text(data, "Temp");
        String tableId = text(data, "tableID");
        String id = text(data, "id");

        String personNo = resolvePersonNo(idCard);
        AccessEvent event = OpenApiRecordMapper.toEvent(device.getDeviceSn(), device.getId(),
                device.getGateId(), inout, openType, resultStatus, idCard, name, personNo, time,
                temperature, rawJson);
        // 用 tableID/id 覆盖幂等键，保证设备重推不会重复入库
        AccessEvent withUuid = new AccessEvent(
                OpenApiRecordMapper.eventUuid(tableId, id, device.getDeviceSn(), event.eventTime(), idCard),
                event.deviceId(), event.deviceSn(), event.gateId(), event.direction(),
                event.eventTime(), event.identifyType(), event.personNo(), event.personName(),
                event.idCardMask(), event.resultStatus(), event.failReason(), event.score(),
                event.temperature(), event.mateFlag(), event.abnormalFlag(), event.source(),
                event.rawJson());

        Optional<Long> inserted = accessEventRepository.insertIfAbsent(withUuid);
        if (inserted.isEmpty()) {
            log.debug("重复记录已忽略 device={} tableID={} id={}", device.getDeviceSn(), tableId, id);
            return null;
        }
        Long eventId = inserted.get();
        String scenePhoto = text(data, "scenePhoto");
        if (scenePhoto != null && !scenePhoto.isBlank()) {
            captureUploader.uploadAsync(eventId, device.getDeviceSn(), withUuid.eventUuid(), scenePhoto);
        }
        visitRecorder.record(withUuid, eventId);
        log.info("通行记录 device={} idno={} openType={} inout={} result={}",
                device.getDeviceSn(), OpenApiRecordMapper.maskIdCard(idCard), openType, inout, resultStatus);
        return eventId;
    }

    /* ---------------- 二维码 / 密码开门 ---------------- */

    /** 二维码推送：设备把码交给我们判定，返回是否开闸（协议 §3.31）。 */
    public QrTicketService.VerifyResult verifyQr(Device device, String code) {
        return qrTicketService.verify(code, null, device.getGateId());
    }

    /* ---------------- 内部工具 ---------------- */

    private List<AccessGrant> activeGrants(Device device) {
        Long gateId = device.getGateId() == null ? 1L : device.getGateId();
        return grantRepository.search(null, AccessGrant.STATUS_ACTIVE, 0, 2000).stream()
                .filter(g -> gateId.equals(g.gateId()))
                .toList();
    }

    private PersonView build(String personNo, Device device) {
        for (AccessGrant grant : activeGrants(device)) {
            if (grant.personNo().equals(personNo)) {
                return build(grant);
            }
        }
        return null;
    }

    /**
     * 按 personNo 反查下发用的 idno：有证件号用证件号，没有就用 personNo。
     *
     * <p>刻意不经过 access_grant —— 删除名单时授权已经撤销，按 grant 查不到人。
     */
    private String idnoOfPerson(String personNo) {
        Optional<Employee> employee = employeeRepository.findByPersonNo(personNo);
        if (employee.isPresent()) {
            Employee e = employee.get();
            String idCard = e.getIdCardEnc() == null ? null : crypto.decrypt(e.getIdCardEnc());
            return idCard == null || idCard.isBlank() ? e.getPersonNo() : idCard;
        }
        Optional<Visitor> visitor = visitorRepository.findByPersonNo(personNo);
        if (visitor.isPresent()) {
            Visitor v = visitor.get();
            String idCard = v.getIdCardEnc() == null ? null : crypto.decrypt(v.getIdCardEnc());
            return idCard == null || idCard.isBlank() ? v.getPersonNo() : idCard;
        }
        return personNo;
    }

    private PersonView findByIdno(Device device, String idno) {
        if (idno == null) {
            return null;
        }
        for (AccessGrant grant : activeGrants(device)) {
            PersonView view = build(grant);
            if (view != null && idno.equals(view.idno())) {
                return view;
            }
        }
        return null;
    }

    private PersonView build(AccessGrant grant) {
        if (grant.subjectType() == AccessGrant.SUBJECT_EMPLOYEE) {
            Optional<Employee> employee = employeeRepository.findById(grant.subjectId());
            if (employee.isEmpty()) {
                return null;
            }
            Employee e = employee.get();
            String idCard = e.getIdCardEnc() == null ? null : crypto.decrypt(e.getIdCardEnc());
            // 员工没有身份证时用品编号当唯一标识（协议只要求 idno 不重复）
            String idno = idCard == null || idCard.isBlank() ? e.getPersonNo() : idCard;
            return new PersonView(grant.personNo(), idno, e.getName(), grant.validFrom(), grant.validTo(),
                    grant.subjectType(), grant.id().intValue(), facePhoto(e.getId()));
        }
        Optional<Visitor> visitor = visitorRepository.findById(grant.subjectId());
        if (visitor.isEmpty()) {
            return null;
        }
        Visitor v = visitor.get();
        String idCard = v.getIdCardEnc() == null ? null : crypto.decrypt(v.getIdCardEnc());
        String idno = idCard == null || idCard.isBlank() ? v.getPersonNo() : idCard;
        return new PersonView(grant.personNo(), idno, v.getName(), grant.validFrom(), grant.validTo(),
                grant.subjectType(), grant.id().intValue(), visitorPhoto(v));
    }

    /** 访客照片 → base64（V14 自助采集后才有）；未采集返回 null，由 passAlgo 兜底。 */
    private String visitorPhoto(Visitor v) {
        String key = v.getFaceOssKey();
        if (key == null || key.isBlank() || !storageService.configured()) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(storageService.getObject(key));
        } catch (Exception e) {
            log.warn("读取访客 {} 照片失败，本次按无照片下发：{}", v.getId(), e.getMessage());
            return null;
        }
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

    /**
     * 设备回传的证件号 → 平台人员编号。
     *
     * <p>必须员工优先：之前只查访客表，导致员工刷脸/刷证的记录 person_no 全为空，
     * 「通行记录」按人汇总（traffic/summary 要求 person_no 非空）就一条都查不出来。
     * 证件号大小写规范化规则员工和访客不一定一致，两种都试一次。
     */
    private String resolvePersonNo(String idCard) {
        if (idCard == null || idCard.isBlank()) {
            return null;
        }
        String raw = idCard.trim();
        for (String candidate : new String[]{raw, raw.toUpperCase()}) {
            String hmac = crypto.hmac(candidate);
            Optional<Employee> employee = employeeRepository.findByIdCardHmac(hmac);
            if (employee.isPresent()) {
                return employee.get().getPersonNo();
            }
            Optional<Visitor> visitor = visitorRepository.findByIdCardHmac(hmac);
            if (visitor.isPresent()) {
                return visitor.get().getPersonNo();
            }
        }
        return null;
    }

    private String personNoOf(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            JsonNode personNo = node.get("personNo");
            return personNo == null || personNo.isNull() ? null : personNo.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer number(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 名单视图（内部使用）。 */
    public record PersonView(String personNo, String idno, String name,
                             LocalDateTime validFrom, LocalDateTime validTo,
                             int subjectType, int personVer, String photoBase64) {
    }

    /**
     * 下发给设备的有效期上限。
     *
     * <p>平台内部用 {@code 9999-12-31} 表示"永久有效"，但设备端按 32 位时间戳换算有效期，
     * 9999 年会被截断成负数，机器上直接显示"已过期"（2026-11-20 真机实测踩到）。
     * 统一切到 2037-12-31（仍在 32 位有符号时间戳范围内），对业务等同于永久。
     */
    static final LocalDateTime DEVICE_MAX_VALID_TO = LocalDateTime.of(2037, 12, 31, 23, 59, 59);

    /** 起始日期为空按"很久以前"下发，避免设备把空值当成"未生效"。 */
    private static LocalDateTime deviceValidFrom(LocalDateTime from) {
        return from == null ? LocalDateTime.of(2000, 1, 1, 0, 0) : from;
    }

    /** 结束日期为空或超过上限时统一截断，原因见 {@link #DEVICE_MAX_VALID_TO}。 */
    static LocalDateTime deviceValidTo(LocalDateTime to) {
        return to == null || to.isAfter(DEVICE_MAX_VALID_TO) ? DEVICE_MAX_VALID_TO : to;
    }

    /** 供控制器判断是否需要补 mac/型号。 */
    public boolean isPlaceholderMac(Device device) {
        return device.getMac() != null && device.getMac().startsWith("PULL-");
    }

    /** 设备已主动拉取某类指令（如参数/时间），把队列里该类指令直接置成功。 */
    public int completeSimpleCommand(Long deviceId, String cmdType, String message) {
        List<PullCommandRepository.Row> rows = commandRepository.listPending(deviceId, cmdType, MAX_BATCH);
        if (rows.isEmpty()) {
            return 0;
        }
        List<Long> ids = rows.stream().map(PullCommandRepository.Row::id).toList();
        commandRepository.markDispatching(ids, LocalDateTime.now());
        commandRepository.markDone(ids, 2, message);
        return ids.size();
    }

    /** 设备上报 mac 时补齐档案。 */
    public void bindMac(Device device, String mac) {        if (mac == null || mac.isBlank() || !isPlaceholderMac(device)) {
            return;
        }
        try {
            deviceRepository.bindMac(device.getId(), mac.trim());
            log.info("为设备 {} 绑定真实 mac={}", device.getDeviceSn(), mac);
        } catch (Exception e) {
            log.warn("绑定 mac 失败 device={} mac={}：{}", device.getDeviceSn(), mac, e.getMessage());
        }
    }
}
