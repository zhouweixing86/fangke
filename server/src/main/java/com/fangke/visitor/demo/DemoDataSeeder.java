package com.fangke.visitor.demo;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.approval.model.ApprovalTask;
import com.fangke.visitor.dispatch.model.AccessGrant;
import com.fangke.visitor.dispatch.model.SyncBatch;
import com.fangke.visitor.dispatch.model.SyncBatchItem;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.job.service.JobCodes;
import com.fangke.visitor.notify.model.Notification;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.vehicle.model.VehicleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据维护：{@code fangke.demo.seed} 取两个值。
 *
 * <ul>
 *   <li>{@code reset}：清空业务表后写入一套自洽的演示数据（演示/联调环境用）。</li>
 *   <li>{@code clean}：只清空业务数据，<b>保留账号、角色、系统参数、审批配置、门点、设备</b>，
 *       用于「演示数据换成真实数据」这一步——真机已经按 SN 建档并绑好了门点，清掉就得重新登记。</li>
 * </ul>
 *
 * <p>为什么必须走 Java 而不是 SQL 脚本：手机号 / 证件号是 AES-GCM 密文 + HMAC 摘要，
 * 密文只能由 {@link CryptoService} 生成，否则页面解密会报错；另外预约 → 双审 → 授权 → 通行
 * 四个环节的数据要互相自洽（状态码、任务、授权门点、事件时间都要对得上）。
 *
 * <p>启动示例：
 * <pre>java -jar visitor-server.jar --fangke.demo.seed=clean</pre>
 *
 * <p><b>警告</b>：两个模式都会 DELETE 业务表，生产环境不要打开。
 */
@Component
@ConditionalOnExpression("'${fangke.demo.seed:}' == 'reset' or '${fangke.demo.seed:}' == 'clean'")
@Order(50)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 按依赖顺序清空的业务表（子表在前）。 */
    private static final List<String> WIPE_TABLES = List.of(
            "access_event_media", "access_event", "vehicle_event", "person_device_state",
            "device_cmd", "device_heartbeat", "device_param_snapshot",
            "sync_batch_item", "sync_batch",
            "notification", "job_run_log", "audit_log", "qr_ticket", "visitor_consent",
            "visit_record", "access_grant", "approval_action", "approval_task",
            "appointment_gate", "appointment_mate", "appointment",
            "visitor", "employee_face", "employee", "blacklist", "device");

    private final JdbcTemplate jdbc;
    private final CryptoService crypto;
    /** 运行模式：reset（清空 + 造数）/ clean（只清空）。 */
    private final String mode;

    private final Map<String, Long> dept = new LinkedHashMap<>();
    private final Map<String, Long> gate = new LinkedHashMap<>();
    private final Map<String, Long> dev = new LinkedHashMap<>();
    private final Map<String, Long> emp = new LinkedHashMap<>();
    private final Map<String, Long> vis = new LinkedHashMap<>();

    private final Map<String, String> empDeptName = new HashMap<>();
    private final Map<String, String> empIdCard = new HashMap<>();
    private final Map<String, String> empName = new HashMap<>();
    private final Map<String, Integer> empFace = new HashMap<>();

    private final Map<String, String> visName = new HashMap<>();
    private final Map<String, String> visIdCard = new HashMap<>();
    private final Map<String, String> visPhone = new HashMap<>();

    private final Map<LocalDate, Integer> apptSeq = new HashMap<>();
    private int eventSeq = 0;
    private int vehicleSeq = 0;

    private LocalDate today;
    private LocalDateTime now;

    public DemoDataSeeder(JdbcTemplate jdbc, CryptoService crypto,
                          @Value("${fangke.demo.seed:}") String mode) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        this.today = LocalDate.now();
        this.now = LocalDateTime.now().withSecond(0).withNano(0);

        if ("clean".equals(mode)) {
            clean();
            return;
        }

        log.warn("======================================================");
        log.warn(" 演示数据重建：清空业务表并写入演示数据（fangke.demo.seed=reset）");
        log.warn("======================================================");
        long begin = System.currentTimeMillis();
        wipe();
        seedOrg();
        seedDevices();
        seedEmployees();
        seedVisitors();
        seedAppointments();
        seedGrants();
        seedAccessEvents();
        seedVehicleEvents();
        seedOperations();
        log.warn(" 演示数据重建完成：部门 {}、门点 {}、设备 {}、员工 {}、访客 {}、通行记录 {} 条、车辆记录 {} 条，用时 {} ms",
                dept.size(), gate.size(), dev.size(), emp.size(), vis.size(),
                eventSeq, vehicleSeq, System.currentTimeMillis() - begin);
    }

    // ==================== 只清空（clean） ====================

    /**
     * 清空业务数据，但保留账号 / 角色 / 系统参数 / 审批配置 / 门点 / 设备。
     *
     * <p>设备必须保留：真机已经按 SN 建档、绑好门点，清掉就要重新等心跳登记再分配一次。
     * 设备本地名单不在这里处理——清空后要么由设备后台「删除全部名单」重置，
     * 要么由平台侧新的下发覆盖。
     */
    private void clean() {
        log.warn("======================================================");
        log.warn(" 清空业务数据（fangke.demo.seed=clean）：保留账号 / 门点 / 设备");
        log.warn("======================================================");
        long begin = System.currentTimeMillis();
        int total = 0;
        for (String table : WIPE_TABLES) {
            if ("device".equals(table)) {
                continue;
            }
            int rows = jdbc.update("DELETE FROM " + table);
            total += rows;
            jdbc.execute("ALTER TABLE " + table + " AUTO_INCREMENT = 1");
        }
        int depts = jdbc.update("DELETE FROM dept");
        jdbc.execute("ALTER TABLE dept AUTO_INCREMENT = 1");
        jdbc.update("UPDATE device SET person_count = 0");
        log.warn(" 清空完成：业务表 {} 行 + 部门 {} 行，用时 {} ms。门点、设备、账号、系统参数未动。",
                total, depts, System.currentTimeMillis() - begin);
    }

    // ==================== 清空 ====================

    private void wipe() {
        for (String table : WIPE_TABLES) {
            int rows = jdbc.update("DELETE FROM " + table);
            log.info("清空 {}：{} 行", table, rows);
        }
        int gates = jdbc.update("DELETE FROM gate WHERE gate_code <> 'UNASSIGNED'");
        int depts = jdbc.update("DELETE FROM dept");
        log.info("清空 gate（保留待分配）：{} 行，dept：{} 行", gates, depts);
        jdbc.execute("INSERT IGNORE INTO gate (gate_code, gate_name, gate_type, status, sort_no, remark) "
                + "VALUES ('UNASSIGNED', '待分配', 9, 1, 999, '设备首次心跳登记时的临时门点')");
        for (String table : WIPE_TABLES) {
            jdbc.execute("ALTER TABLE " + table + " AUTO_INCREMENT = 1");
        }
        jdbc.execute("ALTER TABLE gate AUTO_INCREMENT = 1");
        jdbc.execute("ALTER TABLE dept AUTO_INCREMENT = 1");
    }

    // ==================== 组织：部门 + 门点 ====================

    private void seedOrg() {
        dept.put("ADM", dept("ADM", "行政人事部", 1));
        dept.put("IT", dept("IT", "信息技术部", 2));
        dept.put("PROD", dept("PROD", "生产制造部", 3));
        dept.put("LOGI", dept("LOGI", "物流仓储部", 4));
        dept.put("SEC", dept("SEC", "安全保卫部", 5));

        gate.put("UNASSIGNED", queryLong("SELECT id FROM gate WHERE gate_code = 'UNASSIGNED'"));
        gate.put("MAIN_GATE", gate("MAIN_GATE", "园区大门", 1, 1, "人行闸机 + 车牌识别，双向通行"));
        gate.put("SIDE_GATE", gate("SIDE_GATE", "货运侧门", 2, 2, "货车与货物通道，22:00-06:00 关闭"));
    }

    private long dept(String code, String name, int sortNo) {
        return insert("INSERT INTO dept (dept_code, dept_name, parent_id, sort_no) VALUES (?,?,0,?)",
                code, name, sortNo);
    }

    private long gate(String code, String name, int type, int sortNo, String remark) {
        return insert("INSERT INTO gate (gate_code, gate_name, gate_type, status, sort_no, remark) "
                + "VALUES (?,?,?,1,?,?)", code, name, type, sortNo, remark);
    }

    // ==================== 设备 ====================

    private void seedDevices() {
        String capability = "{\"face\":true,\"idcard\":true,\"qr\":true,\"relay\":true}";
        device("PERSON_MAIN", "T77R781116ZS", "0A:0C:E8:68:11:16", "大门人行闸机", "YC-P208",
                "V2.1.4", "MAIN_GATE", 2, "192.168.1.64", 1, now, 18, 5000, capability,
                "现场首发测试机，进门 / 出门共用一台双向闸机");
        device("PLATE_MAIN", "PLATE-MAIN-01", "0A:0C:E8:68:11:20", "大门车牌识别相机", "FKP-2C",
                "V1.8.0", "MAIN_GATE", 2, "192.168.1.65", 1, now, null, null, capability,
                "识别后由平台判定是否开闸，干接点接车闸");
        device("PERSON_SIDE", "T77R781116ZS-SIDE", "0A:0C:E8:68:11:17", "货运侧门人脸终端", "YC-P208",
                "V2.1.4", "SIDE_GATE", 2, "192.168.1.66", 0, now.minusHours(3).minusMinutes(12), 9, 5000,
                capability, "侧门网线未布，设备暂离线");
    }

    private void device(String key, String sn, String mac, String name, String model, String firmware,
                        String gateCode, int direction, String ip, int online, LocalDateTime heartbeat,
                        Integer personCount, Integer capacity, String capability, String remark) {
        long id = insert("INSERT INTO device (device_sn, mac, device_name, model, firmware, gate_id, "
                        + "direction, channel, ip, port, capability, online_state, last_heartbeat, "
                        + "person_count, capacity, status, remark) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?)",
                sn, mac, name, model, firmware, gate.get(gateCode), direction, "PULL", ip, 8080,
                capability, online, heartbeat, personCount, capacity, remark);
        dev.put(key, id);
    }

    // ==================== 员工 ====================

    private record EmpSeed(String personNo, String empNo, String name, String deptCode, String leaderNo,
                           String title, String plateNo, String phone, String idCard,
                           int status, int faceStatus, LocalDate validFrom, LocalDate validTo,
                           String remark) {
    }

    private void seedEmployees() {
        List<EmpSeed> seeds = List.of(
                new EmpSeed("E000001", "000001", "张伟", "IT", null, "技术总监", "京A88888",
                        "13800138001", "110101199001010011", Employee.STATUS_ACTIVE,
                        Employee.FACE_EFFECTIVE, LocalDate.of(2024, 3, 1), LocalDate.of(2027, 12, 31), null),
                new EmpSeed("E000002", "000002", "李娜", "ADM", "E000001", "行政主管", "京A66113",
                        "13800138002", "110101199203150026", Employee.STATUS_ACTIVE,
                        Employee.FACE_EFFECTIVE, LocalDate.of(2024, 3, 1), LocalDate.of(2027, 12, 31), null),
                new EmpSeed("E000003", "000003", "王强", "PROD", "E000001", "车间主任", "京BA7281",
                        "13800138003", "110101198807220033", Employee.STATUS_ACTIVE,
                        Employee.FACE_EFFECTIVE, LocalDate.of(2024, 3, 1), LocalDate.of(2027, 12, 31), null),
                new EmpSeed("E000004", "000004", "刘洋", "LOGI", "E000003", "调度专员", null,
                        "13800138004", "110101199511080048", Employee.STATUS_ACTIVE,
                        Employee.FACE_UPLOADED, LocalDate.of(2025, 6, 1), LocalDate.of(2027, 12, 31),
                        "照片已上传，待终端识别校准"),
                new EmpSeed("E000005", "000005", "陈静", "ADM", "E000002", "会计", "京N3K921",
                        "13800138005", "110101199407300055", Employee.STATUS_ACTIVE,
                        Employee.FACE_NONE, LocalDate.of(2024, 9, 1), LocalDate.of(2027, 12, 31),
                        "暂未采集人脸，走人证核验"),
                new EmpSeed("E000006", "000006", "赵磊", "SEC", "E000002", "保安队长", null,
                        "13800138006", "110101198509180062", Employee.STATUS_ACTIVE,
                        Employee.FACE_EFFECTIVE, LocalDate.of(2024, 3, 1), LocalDate.of(2027, 12, 31), null),
                new EmpSeed("E000007", "000007", "孙悦", "IT", "E000001", "前端工程师", "京A5Q77",
                        "13800138007", "110101199612040077", Employee.STATUS_DISABLED,
                        Employee.FACE_EFFECTIVE, LocalDate.of(2024, 5, 1), LocalDate.of(2026, 9, 30),
                        "已离职，通行权限已撤销"),
                new EmpSeed("E000008", "000008", "周涛", "PROD", "E000003", "操作工", null,
                        "13800138008", "110101199803260088", Employee.STATUS_PENDING_REVIEW,
                        Employee.FACE_NONE, today.plusDays(1), LocalDate.of(2028, 3, 31),
                        "新入职待审核，明天生效"));

        for (EmpSeed s : seeds) {
            long id = insert("INSERT INTO employee (person_no, emp_no, name, dept_id, title, plate_no, "
                            + "phone_enc, phone_hmac, phone_mask, id_card_enc, id_card_hmac, id_card_mask, "
                            + "status, valid_from, valid_to, face_status, remark, created_by, updated_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,1)",
                    s.personNo(), s.empNo(), s.name(), dept.get(s.deptCode()), s.title(), s.plateNo(),
                    crypto.encrypt(s.phone()), crypto.hmac(s.phone()), CryptoService.maskPhone(s.phone()),
                    crypto.encrypt(s.idCard()), crypto.hmac(s.idCard()), CryptoService.maskIdCard(s.idCard()),
                    s.status(), s.validFrom(), s.validTo(), s.faceStatus(), s.remark());
            emp.put(s.personNo(), id);
            empDeptName.put(s.personNo(), deptName(s.deptCode()));
            empIdCard.put(s.personNo(), s.idCard());
            empName.put(s.personNo(), s.name());
            empFace.put(s.personNo(), s.faceStatus());

            if (s.faceStatus() >= Employee.FACE_UPLOADED) {
                insert("INSERT INTO employee_face (employee_id, oss_key, oss_key_std, width, height, "
                                + "size_bytes, sha256, is_current, uploaded_by) VALUES (?,?,?,?,?,?,?,1,1)",
                        id, "employee/" + id + "/face/latest.jpg", "employee/" + id + "/face/std.jpg",
                        480, 640, 138240, sha256("face-" + s.personNo()));
            }
        }

        for (EmpSeed s : seeds) {
            if (s.leaderNo() != null) {
                jdbc.update("UPDATE employee SET leader_id = ? WHERE id = ?",
                        emp.get(s.leaderNo()), emp.get(s.personNo()));
            }
        }
    }

    private String deptName(String deptCode) {
        return switch (deptCode) {
            case "ADM" -> "行政人事部";
            case "IT" -> "信息技术部";
            case "PROD" -> "生产制造部";
            case "LOGI" -> "物流仓储部";
            case "SEC" -> "安全保卫部";
            default -> deptCode;
        };
    }

    // ==================== 访客 ====================

    private record VisitorSeed(String key, String name, String company, String phone, String idCard,
                               int faceStatus, int visitCount, LocalDate firstVisit) {
    }

    private void seedVisitors() {
        List<VisitorSeed> seeds = List.of(
                new VisitorSeed("wangzhiming", "王志明", "明辉机电工程有限公司", "13700137001",
                        "110101199008120013", 1, 3, today.minusDays(40)),
                new VisitorSeed("liuxiaohong", "刘晓红", "恒通物流有限公司", "13700137002",
                        "110101199105200028", 1, 5, today.minusDays(90)),
                new VisitorSeed("zhengkai", "郑凯", "中建八局第一建设有限公司", "13700137003",
                        "110101198703040035", 1, 1, today),
                new VisitorSeed("zhaomin", "赵敏", "华宇电子科技有限公司", "13700137004",
                        "110101199209150042", 0, 2, today.minusDays(15)),
                new VisitorSeed("sunhao", "孙浩", "迅达电梯维保服务部", "13700137005",
                        "110101198812010059", 0, 1, today),
                new VisitorSeed("qiancheng", "钱程", "顺丰速运城东营业部", "13700137006",
                        "110101199310220066", 1, 6, today.minusDays(60)),
                new VisitorSeed("zhouwu", "周五", "个体运输", "13700137007",
                        "110101199704180073", 0, 1, today.minusDays(1)),
                new VisitorSeed("chenxi", "陈曦", "市生态环境局执法支队", "13700137008",
                        "110101198205090080", 1, 2, today.minusDays(30)),
                new VisitorSeed("mading", "马丁", "德邦快递", "13700137009",
                        "110101199109300097", 0, 4, today.minusDays(1)),
                new VisitorSeed("guoting", "郭婷", "蓝盾安防技术有限公司", "13700137010",
                        "110101199502140104", 1, 1, today));

        int seq = 0;
        for (VisitorSeed s : seeds) {
            seq++;
            String personNo = "V" + s.firstVisit().format(DAY_FMT) + String.format("%04d", seq);
            LocalDateTime firstVisit = s.firstVisit().atTime(9, 0);
            LocalDateTime lastVisit = s.visitCount() > 1
                    ? min(s.firstVisit().plusDays(7).atTime(9, 30), now) : null;
            long id = insert("INSERT INTO visitor (person_no, name, phone_enc, phone_hmac, phone_mask, "
                            + "id_card_enc, id_card_hmac, id_card_mask, company, face_oss_key, face_status, "
                            + "first_visit_at, last_visit_at, visit_count, is_blacklist) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    personNo, s.name(), crypto.encrypt(s.phone()), crypto.hmac(s.phone()),
                    CryptoService.maskPhone(s.phone()),
                    crypto.encrypt(s.idCard()), crypto.hmac(s.idCard()),
                    CryptoService.maskIdCard(s.idCard()), s.company(),
                    s.faceStatus() > 0 ? "visitor/" + s.key() + "/face/latest.jpg" : null,
                    s.faceStatus(), firstVisit, lastVisit, s.visitCount(),
                    "mading".equals(s.key()) ? 1 : 0);
            vis.put(s.key(), id);
            visName.put(s.key(), s.name());
            visIdCard.put(s.key(), s.idCard());
            visPhone.put(s.key(), s.phone());

            insert("INSERT INTO visitor_consent (visitor_id, consent_type, version, agreed_at, ip, ua) "
                            + "VALUES (?,1,'v1.0',?,?,'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)')",
                    id, firstVisit, "192.168.1." + (100 + seq));
        }
    }

    // ==================== 预约 + 审批链 ====================

    private void seedAppointments() {
        LocalDateTime anchor = now;
        LocalDateTime yesterday = today.minusDays(1).atTime(0, 0);

        // A1 待被访人审批（今天 13:00-18:00，指定园区大门）
        long a1 = appointment("wangzhiming", "E000001", "供应商", "设备调试与验收", "沪C5566X",
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_SPECIFIED,
                today.atTime(13, 0), today.atTime(18, 0), anchor.minusHours(1).minusMinutes(10));
        apptGates(a1, 1, gate.get("MAIN_GATE"));
        currentTask(a1, task(a1, 1, ApprovalTask.TYPE_EMPLOYEE, null, emp.get("E000001"),
                ApprovalTask.STATUS_PENDING, anchor.minusHours(1).minusMinutes(10), null));

        // A2 待保安复核（一级已通过，申请"全部门点"→ 通过时快照 2 个门点）
        long a2 = appointment("liuxiaohong", "E000002", "客户", "年度合同商务洽谈", null,
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_ALL,
                today.atTime(10, 0), today.atTime(17, 0), anchor.minusHours(2).minusMinutes(20));
        apptGates(a2, 1, gate.get("MAIN_GATE"));
        jdbc.update("UPDATE appointment SET mate_mode = ?, mate_count = 2 WHERE id = ?",
                Appointment.MATE_SEPARATE, a2);
        mate(a2, "刘晓丽", "110101199305170117", "13700137011");
        mate(a2, "刘晓芳", "110101199611230125", "13700137012");
        long a2L1 = task(a2, 1, ApprovalTask.TYPE_EMPLOYEE, null, emp.get("E000002"),
                ApprovalTask.STATUS_PASSED, anchor.minusHours(2).minusMinutes(20),
                anchor.minusHours(2).minusMinutes(2));
        action(a2L1, a2, emp.get("E000002"), 1, 1, "老客户来访，同意接待",
                gateSnapshot("MAIN_GATE", "SIDE_GATE"), anchor.minusHours(2).minusMinutes(2));
        apptGates(a2, 2, gate.get("MAIN_GATE"), gate.get("SIDE_GATE"));
        setStatus(a2, Appointment.PENDING_L2);
        long a2L2 = task(a2, 2, ApprovalTask.TYPE_ROLE, "GUARD", 0, ApprovalTask.STATUS_PENDING,
                anchor.minusHours(2), null);
        currentTask(a2, a2L2);
        remind(a2L2, a2, anchor.minusMinutes(5));

        // A3 已签到（在场）：随行施工车辆也在园区
        LocalDateTime zhengIn = max(today.atTime(9, 20), anchor.minusMinutes(95));
        long a3 = appointment("zhengkai", "E000003", "施工方", "产线设备安装施工", "苏A5D77",
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_SPECIFIED,
                today.atTime(9, 0), today.atTime(18, 0), anchor.minusHours(3).minusMinutes(20));
        apptGates(a3, 1, gate.get("MAIN_GATE"));
        approveChain(a3, "E000003", anchor.minusHours(3).minusMinutes(20), anchor.minusHours(3),
                "施工已报备，注意安全交底", gateSnapshot("MAIN_GATE"));
        apptGates(a3, 2, gate.get("MAIN_GATE"));
        setStatus(a3, Appointment.CHECKED_IN);

        // A4 已签退：访客车辆进 + 出
        LocalDateTime zhaoIn = max(today.atTime(8, 45), anchor.minusHours(3).minusMinutes(5));
        LocalDateTime zhaoOut = max(zhaoIn.plusMinutes(40), anchor.minusMinutes(40));
        long a4 = appointment("zhaomin", "E000004", "客户", "月度对账与提货", "苏E8822K",
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_ALL,
                today.atTime(8, 30), today.atTime(12, 0), anchor.minusHours(4));
        apptGates(a4, 1, gate.get("MAIN_GATE"));
        approveChain(a4, "E000004", anchor.minusHours(4), anchor.minusHours(3).minusMinutes(40),
                "对账资料已备好", gateSnapshot("MAIN_GATE", "SIDE_GATE"));
        apptGates(a4, 2, gate.get("MAIN_GATE"), gate.get("SIDE_GATE"));
        setStatus(a4, Appointment.CHECKED_OUT);
        jdbc.update("UPDATE appointment SET end_time = ? WHERE id = ?",
                max(today.atTime(12, 0), zhaoOut.plusMinutes(10)), a4);

        // A5 已驳回
        long a5 = appointment("sunhao", "E000001", "供应商", "电梯季度维保", null,
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_SPECIFIED,
                today.atTime(14, 0), today.atTime(16, 0), anchor.minusHours(5));
        apptGates(a5, 1, gate.get("MAIN_GATE"));
        long a5L1 = task(a5, 1, ApprovalTask.TYPE_EMPLOYEE, null, emp.get("E000001"),
                ApprovalTask.STATUS_REJECTED, anchor.minusHours(5), anchor.minusHours(4).minusMinutes(30));
        action(a5L1, a5, emp.get("E000001"), 1, 2, "今日车间停产检修，改约下周", null,
                anchor.minusHours(4).minusMinutes(30));
        jdbc.update("UPDATE appointment SET status = ?, reject_reason = ? WHERE id = ?",
                Appointment.REJECTED, "今日车间停产检修，改约下周", a5);

        // A6 现场登记（门卫自助登记）+ 当场放行，走二维码
        LocalDateTime qianIn = max(today.atTime(8, 0), anchor.minusMinutes(25));
        long a6 = appointment("qiancheng", "E000002", "快递", "派送办公用品", null,
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_SPECIFIED,
                qianIn.minusMinutes(3), today.atTime(18, 0), qianIn.minusMinutes(5));
        jdbc.update("UPDATE appointment SET source = ? WHERE id = ?", Appointment.SOURCE_ONSITE, a6);
        apptGates(a6, 1, gate.get("MAIN_GATE"));
        approveChain(a6, "E000002", qianIn.minusMinutes(5), qianIn.minusMinutes(4),
                "门卫现场登记，身份已核验", gateSnapshot("MAIN_GATE"));
        apptGates(a6, 2, gate.get("MAIN_GATE"));
        setStatus(a6, Appointment.CHECKED_IN);

        // A7 昨天：已过期未到访
        LocalDateTime a7Created = yesterday.minusDays(1).plusHours(16).plusMinutes(20);
        long a7 = appointment("zhouwu", "E000005", "供应商", "面试（仓储岗）", null,
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_SPECIFIED,
                yesterday.plusHours(9), yesterday.plusHours(18), a7Created);
        apptGates(a7, 1, gate.get("MAIN_GATE"));
        approveChain(a7, "E000005", a7Created, a7Created.plusMinutes(45),
                "约面试，请前台接待", gateSnapshot("MAIN_GATE"));
        apptGates(a7, 2, gate.get("MAIN_GATE"));
        setStatus(a7, Appointment.EXPIRED);

        // A8 明天：已通过并下发（全部门点）
        long a8 = appointment("chenxi", "E000003", "政府检查", "环保执法例行检查", "苏A90001",
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_ALL,
                today.plusDays(1).atTime(9, 0), today.plusDays(1).atTime(17, 0),
                today.atTime(9, 40));
        apptGates(a8, 1, gate.get("MAIN_GATE"));
        approveChain(a8, "E000003", today.atTime(9, 40), today.atTime(10, 14),
                "配合检查，全程陪同", gateSnapshot("MAIN_GATE", "SIDE_GATE"));
        apptGates(a8, 2, gate.get("MAIN_GATE"), gate.get("SIDE_GATE"));
        setStatus(a8, Appointment.GRANTED);
        jdbc.update("UPDATE appointment SET granted_at = ? WHERE id = ?", today.atTime(10, 15), a8);

        // A9 昨天：访客主动撤回
        long a9 = appointment("mading", "E000004", "快递", "大件货物送达", null,
                gate.get("SIDE_GATE"), Appointment.GATE_SCOPE_SPECIFIED,
                yesterday.plusHours(14), yesterday.plusHours(16), yesterday.plusHours(10).plusMinutes(30));
        apptGates(a9, 1, gate.get("SIDE_GATE"));
        currentTask(a9, task(a9, 1, ApprovalTask.TYPE_EMPLOYEE, null, emp.get("E000004"),
                ApprovalTask.STATUS_INVALID, yesterday.plusHours(10).plusMinutes(30), null));
        jdbc.update("UPDATE appointment SET status = ?, cancel_reason = ? WHERE id = ?",
                Appointment.WITHDRAWN, "访客主动撤回：货物改由快递柜代收", a9);

        // A10 今天：待保安复核（安防巡检）
        long a10 = appointment("guoting", "E000006", "供应商", "安防设备巡检", null,
                gate.get("MAIN_GATE"), Appointment.GATE_SCOPE_SPECIFIED,
                today.atTime(15, 0), today.atTime(17, 0), anchor.minusMinutes(50));
        apptGates(a10, 1, gate.get("MAIN_GATE"));
        long a10L1 = task(a10, 1, ApprovalTask.TYPE_EMPLOYEE, null, emp.get("E000006"),
                ApprovalTask.STATUS_PASSED, anchor.minusMinutes(50), anchor.minusMinutes(35));
        action(a10L1, a10, emp.get("E000006"), 1, 1, "巡检已报备，请门卫确认",
                gateSnapshot("MAIN_GATE"), anchor.minusMinutes(35));
        apptGates(a10, 2, gate.get("MAIN_GATE"));
        setStatus(a10, Appointment.PENDING_L2);
        long a10L2 = task(a10, 2, ApprovalTask.TYPE_ROLE, "GUARD", 0, ApprovalTask.STATUS_PENDING,
                anchor.minusMinutes(35), null);
        currentTask(a10, a10L2);

        // 访客到访事件（person_no 必须是访客编号 V+日期+序号，不能是内部 key）
        String zhengNo = visitorPersonNo("zhengkai");
        String zhaoNo = visitorPersonNo("zhaomin");
        String qianNo = visitorPersonNo("qiancheng");
        long zhengEvent = access(zhengIn, 1, "PERSON_MAIN", "MAIN_GATE", 0, zhengNo, null, 1);
        long zhaoInEvent = access(zhaoIn, 1, "PERSON_MAIN", "MAIN_GATE", 2, zhaoNo, null, 1);
        long zhaoOutEvent = access(zhaoOut, 0, "PERSON_MAIN", "MAIN_GATE", 2, zhaoNo, null, 1);
        long qianEvent = access(qianIn, 1, "PERSON_MAIN", "MAIN_GATE", 5, qianNo, null, 1);

        // 访客车辆进出
        vehicle(zhengIn.minusMinutes(4), "PLATE_MAIN", "MAIN_GATE", "苏A5D77", 1,
                VehicleEvent.TYPE_VISITOR, vis.get("zhengkai"), "V-VISITOR", VehicleEvent.RESULT_PASS, null);
        vehicle(zhaoIn.minusMinutes(3), "PLATE_MAIN", "MAIN_GATE", "苏E8822K", 1,
                VehicleEvent.TYPE_VISITOR, vis.get("zhaomin"), "V-VISITOR", VehicleEvent.RESULT_PASS, null);
        vehicle(zhaoOut.plusMinutes(2), "PLATE_MAIN", "MAIN_GATE", "苏E8822K", 0,
                VehicleEvent.TYPE_VISITOR, vis.get("zhaomin"), "V-VISITOR", VehicleEvent.RESULT_PASS, null);

        // 到访记录
        insert("INSERT INTO visit_record (appointment_id, visitor_id, gate_id, checkin_at, checkin_event, "
                        + "visit_date, status) VALUES (?,?,?,?,?,?,1)",
                a3, vis.get("zhengkai"), gate.get("MAIN_GATE"), zhengIn, zhengEvent, today);
        insert("INSERT INTO visit_record (appointment_id, visitor_id, gate_id, checkin_at, checkin_event, "
                        + "checkout_at, checkout_event, checkout_type, visit_date, status) "
                        + "VALUES (?,?,?,?,?,?,?,1,?,2)",
                a4, vis.get("zhaomin"), gate.get("MAIN_GATE"), zhaoIn, zhaoInEvent,
                zhaoOut, zhaoOutEvent, today);
        insert("INSERT INTO visit_record (appointment_id, visitor_id, gate_id, checkin_at, checkin_event, "
                        + "visit_date, status) VALUES (?,?,?,?,?,?,1)",
                a6, vis.get("qiancheng"), gate.get("MAIN_GATE"), qianIn, qianEvent, today);

        // 二维码票据（现场登记的快递员走二维码）
        insert("INSERT INTO qr_ticket (appointment_id, appointment_no, nonce, code_hash, status, use_count, "
                        + "issued_channel, issued_by, issued_at, expire_at, last_used_at) "
                        + "VALUES (?,?,?,?,1,1,'GUARD',?,?,?,?)",
                a6, appointmentNo(a6), "8F2C1A9D", sha256("DEMO-QR-" + a6),
                guardUserId(), qianIn.minusMinutes(4), today.atTime(18, 0), qianIn);

        seedNotifications(a1, a3, a5, a7, a8, a10, anchor, zhengIn);

        log.info("预约演示数据就绪：A1={} A2={} A3={} A4={} A5={} A6={} A7={} A8={} A9={} A10={}",
                a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
    }

    private void seedNotifications(long a1, long a3, long a5, long a7, long a8, long a10,
                                   LocalDateTime anchor, LocalDateTime zhengIn) {
        notify(Notification.RECEIVER_EMPLOYEE, emp.get("E000001"), "APPROVAL_TODO", "新的访客审批",
                visName.get("wangzhiming") + "（明辉机电）申请今日 10:00-18:00 到访，事由：设备调试与验收。",
                appointmentNo(a1), anchor.minusHours(1).minusMinutes(9), null);
        notify(Notification.RECEIVER_EMPLOYEE, emp.get("E000006"), "APPROVAL_TODO", "新的访客审批",
                visName.get("guoting") + "（蓝盾安防）申请今日 15:00-17:00 到访，事由：安防设备巡检。",
                appointmentNo(a10), anchor.minusMinutes(49), anchor.minusMinutes(30));
        notify(Notification.RECEIVER_VISITOR, vis.get("zhengkai"), "APPROVAL_PASSED", "预约已通过",
                "你的来访已通过审批，可在「我的预约」出示通行码，或到场刷脸进入园区大门。",
                appointmentNo(a3), anchor.minusHours(3), anchor.minusHours(2).minusMinutes(50));
        notify(Notification.RECEIVER_VISITOR, vis.get("chenxi"), "APPROVAL_PASSED", "预约已通过",
                "你的来访已通过审批，可通行门点：园区大门、货运侧门。",
                appointmentNo(a8), today.atTime(10, 16), null);
        notify(Notification.RECEIVER_VISITOR, vis.get("sunhao"), "APPROVAL_REJECTED", "预约被驳回",
                "你的来访被驳回，原因：今日车间停产检修，改约下周。",
                appointmentNo(a5), anchor.minusHours(4).minusMinutes(29), null);
        notify(Notification.RECEIVER_VISITOR, vis.get("zhouwu"), "APPOINTMENT_EXPIRED", "预约已过期",
                "你昨天的来访预约已过期未到访，如需来访请重新提交预约。",
                appointmentNo(a7), today.atTime(0, 5), null);
        notify(Notification.RECEIVER_EMPLOYEE, emp.get("E000003"), "VISIT_CHECKIN", "访客已到场",
                "郑凯 已到场（产线设备安装施工），请做好接待。",
                appointmentNo(a3), zhengIn, zhengIn.plusMinutes(2));
    }

    private long appointment(String visitorKey, String empKey, String visitorType, String purpose,
                             String plateNo, long gateId, int gateScope,
                             LocalDateTime start, LocalDateTime end, LocalDateTime createdAt) {
        String phone = visPhone.get(visitorKey);
        return insert("INSERT INTO appointment (appointment_no, source, visitor_id, visit_employee_id, "
                        + "visit_phone_enc, visit_phone_mask, visitor_type, purpose, plate_no, department, "
                        + "gate_id, gate_scope, start_time, end_time, mate_mode, mate_count, approval_level, "
                        + "status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                apptNo(createdAt.toLocalDate()), Appointment.SOURCE_SELF, vis.get(visitorKey),
                emp.get(empKey), crypto.encrypt(phone), CryptoService.maskPhone(phone),
                visitorType, purpose, plateNo, empDeptName.get(empKey), gateId, gateScope,
                start, end, Appointment.MATE_TOGETHER, 0, 2, Appointment.PENDING_L1, createdAt);
    }

    private void mate(long apptId, String name, String idCard, String phone) {
        insert("INSERT INTO appointment_mate (appointment_id, name, id_card_enc, id_card_hmac, id_card_mask, "
                        + "phone_enc, phone_mask, is_main) VALUES (?,?,?,?,?,?,?,0)",
                apptId, name, crypto.encrypt(idCard), crypto.hmac(idCard), CryptoService.maskIdCard(idCard),
                crypto.encrypt(phone), CryptoService.maskPhone(phone));
    }

    private void apptGates(long apptId, int stage, Long... gateIds) {
        for (Long gateId : gateIds) {
            insert("INSERT INTO appointment_gate (appointment_id, gate_id, stage) VALUES (?,?,?)",
                    apptId, gateId, stage);
        }
    }

    private long task(long apptId, int level, int assigneeType, String role, long assigneeId,
                      int status, LocalDateTime createdAt, LocalDateTime finishedAt) {
        return insert("INSERT INTO approval_task (appointment_id, level, assignee_type, assignee_role, "
                        + "assignee_id, status, deadline_at, finished_at, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                apptId, level, assigneeType, role, assigneeId, status,
                createdAt.plusHours(4), finishedAt, createdAt);
    }

    private void action(long taskId, long apptId, long operatorId, int operatorType, int action,
                        String comment, String snapshot, LocalDateTime at) {
        insert("INSERT INTO approval_action (task_id, appointment_id, operator_id, action, operator_type, "
                        + "comment, gate_snapshot, created_at) VALUES (?,?,?,?,?,?,?,?)",
                taskId, apptId, operatorId, action, operatorType, comment, snapshot, at);
    }

    private void remind(long taskId, long apptId, LocalDateTime at) {
        action(taskId, apptId, guardUserId(), 2, 5, "二级复核超过 30 分钟未处理，已催办一次", null, at);
        jdbc.update("UPDATE approval_task SET reminder_count = 1, last_reminder_at = ? WHERE id = ?", at, taskId);
    }

    /** 一级（被访人）→ 二级（保安角色）都通过的标准链，落 2 条动作 + 2 个任务。 */
    private void approveChain(long apptId, String empKey, LocalDateTime created, LocalDateTime passed,
                              String comment, String snapshot) {
        long l1 = task(apptId, 1, ApprovalTask.TYPE_EMPLOYEE, null, emp.get(empKey),
                ApprovalTask.STATUS_PASSED, created, passed);
        action(l1, apptId, emp.get(empKey), 1, 1, comment, snapshot, passed);
        long l2 = task(apptId, 2, ApprovalTask.TYPE_ROLE, "GUARD", 0, ApprovalTask.STATUS_PASSED,
                passed, passed.plusMinutes(1));
        action(l2, apptId, guardUserId(), 2, 1, "门卫复核通过，已下发终端", snapshot, passed.plusMinutes(1));
        jdbc.update("UPDATE appointment SET status = ?, granted_at = ? WHERE id = ?",
                Appointment.GRANTED, passed.plusMinutes(1), apptId);
    }

    private void setStatus(long apptId, int status) {
        jdbc.update("UPDATE appointment SET status = ? WHERE id = ?", status, apptId);
    }

    private void currentTask(long apptId, long taskId) {
        jdbc.update("UPDATE appointment SET current_task_id = ? WHERE id = ?", taskId, apptId);
    }

    private String gateSnapshot(String... keys) {
        List<String> ids = new ArrayList<>();
        for (String key : keys) {
            ids.add(String.valueOf(gate.get(key)));
        }
        return String.join(",", ids);
    }

    private long guardUserId() {
        return queryLong("SELECT id FROM sys_user WHERE username = 'guard01'");
    }

    private String appointmentNo(long apptId) {
        List<String> rows = jdbc.query("SELECT appointment_no FROM appointment WHERE id = ?",
                (rs, i) -> rs.getString(1), apptId);
        return rows.isEmpty() ? "A-UNKNOWN" : rows.get(0);
    }

    private String apptNo(LocalDate day) {
        int seq = apptSeq.merge(day, 1, Integer::sum);
        return "A" + day.format(DAY_FMT) + String.format("%05d", seq);
    }

    // ==================== 授权 ====================

    private void seedGrants() {
        for (Map.Entry<String, Long> entry : emp.entrySet()) {
            String personNo = entry.getKey();
            Integer status = queryInt("SELECT status FROM employee WHERE id = ?", entry.getValue());
            List<LocalDate> validFrom = jdbc.query("SELECT valid_from FROM employee WHERE id = ?",
                    (rs, i) -> rs.getObject(1, LocalDate.class), entry.getValue());
            if (status == null || status != Employee.STATUS_ACTIVE || validFrom.isEmpty()
                    || validFrom.get(0) == null || validFrom.get(0).isAfter(today)) {
                continue;
            }
            for (String gateKey : List.of("MAIN_GATE", "SIDE_GATE")) {
                insert("INSERT INTO access_grant (subject_type, subject_id, person_no, appointment_id, gate_id, "
                                + "id_card_hmac, valid_from, valid_to, status) VALUES (?,?,?,NULL,?,?,?,?,?)",
                        AccessGrant.SUBJECT_EMPLOYEE, entry.getValue(), personNo, gate.get(gateKey),
                        crypto.hmac(empIdCard.get(personNo)),
                        validFrom.get(0).atStartOfDay(), validFrom.get(0).plusYears(2).atTime(23, 59, 59),
                        AccessGrant.STATUS_ACTIVE);
            }
        }

        grantVisitor("zhengkai", AccessGrant.STATUS_ACTIVE, "MAIN_GATE");
        grantVisitor("zhaomin", AccessGrant.STATUS_ACTIVE, "MAIN_GATE", "SIDE_GATE");
        grantVisitor("qiancheng", AccessGrant.STATUS_ACTIVE, "MAIN_GATE");
        grantVisitor("chenxi", AccessGrant.STATUS_ACTIVE, "MAIN_GATE", "SIDE_GATE");
        grantVisitor("zhouwu", AccessGrant.STATUS_EXPIRED, "MAIN_GATE");

        for (String personNo : List.of("E000001", "E000002", "E000003", "E000004", "E000005", "E000006")) {
            state(dev.get("PERSON_MAIN"), personNo, 1, 1, false);
            state(dev.get("PERSON_SIDE"), personNo, 1, null, true);
        }
        for (String key : List.of("zhengkai", "zhaomin", "qiancheng", "chenxi")) {
            String personNo = visitorPersonNo(key);
            state(dev.get("PERSON_MAIN"), personNo, 1, 1, false);
            state(dev.get("PERSON_SIDE"), personNo, 1, null, true);
        }

        LocalDateTime started = now.minusMinutes(35);
        long batchId = insert("INSERT INTO sync_batch (batch_no, trigger_type, gate_id, total_count, "
                        + "success_count, fail_count, status, started_at, finished_at) VALUES (?,?,?,?,?,?,?,?,?)",
                "B" + started.format(BATCH_FMT) + "01", SyncBatch.TRIGGER_APPROVAL, gate.get("MAIN_GATE"),
                6, 6, 0, SyncBatch.STATUS_SUCCESS, started, started.plusSeconds(18));
        for (String personNo : List.of("E000001", "E000002", "E000003", "E000004", "E000005", "E000006")) {
            insert("INSERT INTO sync_batch_item (batch_id, device_id, person_no, action, status, acked_at) "
                            + "VALUES (?,?,?,?,1,?)",
                    batchId, dev.get("PERSON_MAIN"), personNo, SyncBatchItem.ACTION_UPSERT,
                    started.plusSeconds(6));
        }
    }

    private void grantVisitor(String key, int status, String... gateKeys) {
        List<Long> apptIds = jdbc.query("SELECT id FROM appointment WHERE visitor_id = ? "
                + "ORDER BY start_time DESC LIMIT 1", (rs, i) -> rs.getLong(1), vis.get(key));
        if (apptIds.isEmpty()) {
            return;
        }
        long apptId = apptIds.get(0);
        List<LocalDateTime[]> windows = jdbc.query(
                "SELECT start_time, end_time FROM appointment WHERE id = ?",
                (rs, i) -> new LocalDateTime[]{
                        rs.getTimestamp(1).toLocalDateTime(), rs.getTimestamp(2).toLocalDateTime()},
                apptId);
        for (String gateKey : gateKeys) {
            insert("INSERT INTO access_grant (subject_type, subject_id, person_no, appointment_id, gate_id, "
                            + "id_card_hmac, valid_from, valid_to, status) VALUES (?,?,?,?,?,?,?,?,?)",
                    AccessGrant.SUBJECT_VISITOR, vis.get(key), visitorPersonNo(key), apptId,
                    gate.get(gateKey), crypto.hmac(visIdCard.get(key)),
                    windows.get(0)[0], windows.get(0)[1], status);
        }
    }

    private String visitorPersonNo(String key) {
        List<String> rows = jdbc.query("SELECT person_no FROM visitor WHERE id = ?",
                (rs, i) -> rs.getString(1), vis.get(key));
        return rows.isEmpty() ? key : rows.get(0);
    }

    private void state(long deviceId, String personNo, int expect, Integer actual, boolean diff) {
        insert("INSERT INTO person_device_state (device_id, person_no, expect_state, actual_state, "
                        + "last_synced_at, last_checked_at, diff_flag) VALUES (?,?,?,?,?,?,?)",
                deviceId, personNo, expect, actual, now.minusMinutes(12), now.minusMinutes(9), diff ? 1 : 0);
    }

    // ==================== 通行记录 ====================

    private void seedAccessEvents() {
        List<String> active = List.of("E000001", "E000002", "E000003", "E000004", "E000005", "E000006");
        int workdays = 0;
        for (int back = 6; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            workdays++;
            int idx = 0;
            for (String personNo : active) {
                int inMin = 2 + (idx * 7 + back * 5) % 27;
                int outMin = 28 + (idx * 5 + back * 3) % 25;
                LocalDateTime in = day.atTime(8, inMin, 12 + idx);
                LocalDateTime out = day.atTime(17, outMin, 40 + idx);
                if (!in.isAfter(now)) {
                    access(in, 1, "PERSON_MAIN", "MAIN_GATE", identifyOf(personNo), personNo, null, 1);
                }
                if (!out.isAfter(now)) {
                    access(out, 0, "PERSON_MAIN", "MAIN_GATE", identifyOf(personNo), personNo, null, 1);
                }
                idx++;
            }
        }

        // 被拒记录：未预约访客、员工非授权时段、待审批访客
        String wangNo = visitorPersonNo("wangzhiming");
        access(today.minusDays(1).atTime(8, 15, 6), 1, "PERSON_MAIN", "MAIN_GATE", 0, wangNo,
                "未授权（当日无有效预约）", 0);
        access(today.minusDays(2).atTime(23, 40, 22), 1, "PERSON_MAIN", "MAIN_GATE", 2, "E000003",
                "未授权（非工作时间）", 0);
        access(now.minusHours(1).minusMinutes(40), 1, "PERSON_MAIN", "MAIN_GATE", 0, wangNo,
                "未授权（预约待审批）", 0);

        log.info("员工通行记录生成：{} 个工作日 × {} 人（含被拒 3 条），共 {} 条",
                workdays, active.size(), eventSeq);
    }

    private int identifyOf(String personNo) {
        Integer face = empFace.get(personNo);
        return face != null && face >= Employee.FACE_UPLOADED ? 0 : 2;
    }

    private long access(LocalDateTime at, int direction, String deviceKey, String gateKey, int identifyType,
                        String personNo, String failReason, int result) {
        eventSeq++;
        String name;
        String idCardMask;
        if (personNo.startsWith("E")) {
            name = empName.get(personNo);
            idCardMask = CryptoService.maskIdCard(empIdCard.get(personNo));
        } else {
            name = visitorName(personNo);
            idCardMask = visitorIdCardMask(personNo);
        }
        return insert("INSERT INTO access_event (event_uuid, device_id, device_sn, gate_id, direction, "
                        + "event_time, identify_type, person_no, person_name, id_card_mask, result_status, "
                        + "fail_reason, score, source, raw, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)",
                "demo-ae-" + String.format("%04d", eventSeq), dev.get(deviceKey), deviceSn(deviceKey),
                gate.get(gateKey), direction, at, identifyType, personNo, name, idCardMask,
                result, failReason, result == 1 ? 95.5 : null,
                "{\"demo\":true,\"seq\":" + eventSeq + "}", at.plusSeconds(3));
    }

    private String deviceSn(String deviceKey) {
        List<String> rows = jdbc.query("SELECT device_sn FROM device WHERE id = ?",
                (rs, i) -> rs.getString(1), dev.get(deviceKey));
        return rows.isEmpty() ? deviceKey : rows.get(0);
    }

    private String visitorName(String personNo) {
        List<String> rows = jdbc.query("SELECT name FROM visitor WHERE person_no = ?",
                (rs, i) -> rs.getString(1), personNo);
        return rows.isEmpty() ? personNo : rows.get(0);
    }

    private String visitorIdCardMask(String personNo) {
        List<String> rows = jdbc.query("SELECT id_card_mask FROM visitor WHERE person_no = ?",
                (rs, i) -> rs.getString(1), personNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void seedVehicleEvents() {
        Map<String, String> plateOf = new LinkedHashMap<>();
        plateOf.put("E000001", "京A88888");
        plateOf.put("E000003", "京BA7281");
        for (int back = 6; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            for (Map.Entry<String, String> car : plateOf.entrySet()) {
                String personNo = car.getKey();
                LocalDateTime in = day.atTime(8, 12 + (back * 3) % 15, 20);
                LocalDateTime out = day.atTime(17, 41 + (back * 4) % 12, 30);
                if (!in.isAfter(now)) {
                    vehicle(in, "PLATE_MAIN", "MAIN_GATE", car.getValue(), 1, VehicleEvent.TYPE_EMPLOYEE,
                            emp.get(personNo), personNo, VehicleEvent.RESULT_PASS, null);
                }
                if (!out.isAfter(now)) {
                    vehicle(out, "PLATE_MAIN", "MAIN_GATE", car.getValue(), 0, VehicleEvent.TYPE_EMPLOYEE,
                            emp.get(personNo), personNo, VehicleEvent.RESULT_PASS, null);
                }
            }
        }
        vehicle(today.minusDays(1).atTime(10, 22, 5), "PLATE_MAIN", "MAIN_GATE", "京Q00000", 1, null,
                null, null, VehicleEvent.RESULT_DENY,
                "未登记车辆（车牌未绑定员工，也没有今日访客预约）");
        fixVehiclePersonNo();
        log.info("车辆记录生成：{} 条", vehicleSeq);
    }

    private void vehicle(LocalDateTime at, String deviceKey, String gateKey, String plate, int direction,
                         Integer matchedType, Long matchedId, String personNo, int result, String failReason) {
        vehicleSeq++;
        insert("INSERT INTO vehicle_event (event_uuid, device_sn, gate_id, plate_no, direction, event_time, "
                        + "confidence, matched_type, matched_id, matched_name, person_no, result_status, "
                        + "fail_reason, raw_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "demo-ve-" + String.format("%04d", vehicleSeq), deviceSn(deviceKey), gate.get(gateKey),
                plate, direction, at, 96, matchedType, matchedId,
                matchedId == null ? null : empName.get(personNo), personNo, result,
                failReason, "{\"demo\":true,\"confidence\":96}", at.plusSeconds(2));
    }

    /** 车辆事件的 personNo 先按规则写占位值，再按 matched 关系回填成真实编号（E/V 前缀）。 */
    private void fixVehiclePersonNo() {
        jdbc.update("UPDATE vehicle_event v JOIN employee e ON v.matched_type = 1 AND v.matched_id = e.id "
                + "SET v.person_no = e.person_no, v.matched_name = e.name");
        jdbc.update("UPDATE vehicle_event v JOIN visitor s ON v.matched_type = 2 AND v.matched_id = s.id "
                + "SET v.person_no = s.person_no, v.matched_name = s.name");
    }

    // ==================== 运维类数据 ====================

    private void seedOperations() {
        for (String deviceKey : List.of("PERSON_MAIN", "PLATE_MAIN")) {
            for (int k = 0; k < 6; k++) {
                LocalDateTime at = now.minusMinutes(5L * k);
                insert("INSERT INTO device_heartbeat (device_id, device_sn, device_ip, person_count, "
                                + "device_time, clock_skew_s, raw) VALUES (?,?,?,?,?,?,?)",
                        dev.get(deviceKey), deviceSn(deviceKey), deviceIp(deviceKey),
                        "PERSON_MAIN".equals(deviceKey) ? 18 : null, at, k, "{\"demo\":true}");
            }
        }
        for (int k = 0; k < 2; k++) {
            insert("INSERT INTO device_heartbeat (device_id, device_sn, device_ip, person_count, "
                            + "device_time, clock_skew_s, raw) VALUES (?,?,?,?,?,?,?)",
                    dev.get("PERSON_SIDE"), deviceSn("PERSON_SIDE"), deviceIp("PERSON_SIDE"), 9,
                    now.minusHours(3).minusMinutes(12 + 30L * k), 0, "{\"demo\":true,\"offline\":true}");
        }

        String[][] jobs = {
                {JobCodes.APPROVAL_REMIND, "1", "1", "催办 1 条超时审批任务"},
                {JobCodes.APPOINTMENT_EXPIRE, "1", "1", "1 张预约单置为已过期"},
                {JobCodes.VISIT_AUTO_CHECKOUT, "1", "0", "无超时未签离访客"},
                {JobCodes.GRANT_CLEANUP, "1", "2", "过期访客授权置为失效"},
                {JobCodes.QR_CLEANUP, "1", "3", "清理过期二维码票据"},
                {JobCodes.DATA_RETENTION, "1", "0", "保留期内无需清理"},
                {JobCodes.NOTIFICATION_RETRY, "2", "0", "短信通道超时，稍后重试"}
        };
        int minutesAgo = 12;
        for (String[] job : jobs) {
            LocalDateTime started = now.minusMinutes(minutesAgo);
            insert("INSERT INTO job_run_log (job_code, started_at, finished_at, status, affected, message) "
                            + "VALUES (?,?,?,?,?,?)",
                    job[0], started, started.plusSeconds(2L + Integer.parseInt(job[2])),
                    Integer.parseInt(job[1]), Integer.parseInt(job[2]), job[3]);
            minutesAgo += 95;
        }

        Object[][] audits = {
                {"EMPLOYEE", "CREATE", "E000001 张伟", "/api/admin/employee", now.minusDays(3).minusHours(2)},
                {"EMPLOYEE", "UPDATE", "E000004 刘洋", "/api/admin/employee/4", now.minusDays(2).minusHours(5)},
                {"EMPLOYEE", "DISABLE", "E000007 孙悦", "/api/admin/employee/7/status", now.minusDays(2)},
                {"DEPT", "CREATE", "SEC 安全保卫部", "/api/admin/dept", now.minusDays(6)},
                {"GATE", "CREATE", "SIDE_GATE 货运侧门", "/api/admin/gate", now.minusDays(6).plusHours(1)},
                {"DEVICE", "UPDATE", "T77R781116ZS", "/api/admin/device/1", now.minusDays(1).minusHours(3)},
                {"APPROVAL", "PASS", "预约单二级复核", "/api/guard/approval/task/pass", now.minusHours(3)},
                {"APPROVAL", "REJECT", "孙浩 电梯维保", "/api/approval/task/reject", now.minusHours(4).minusMinutes(30)},
                {"BLACKLIST", "CREATE", "证件号 110101********0097", "/api/admin/blacklist", now.minusHours(6)},
                {"USER", "LOGIN", "guard01", "/api/auth/login", now.minusHours(1)},
                {"USER", "LOGIN", "admin", "/api/auth/login", now.minusMinutes(40)},
                {"REPORT", "EXPORT", "通行明细", "/api/admin/report/daily", now.minusMinutes(25)}
        };
        for (Object[] audit : audits) {
            insert("INSERT INTO audit_log (user_id, user_name, module, action, target, request_uri, request_ip, "
                            + "detail, result, created_at) VALUES (?,?,?,?,?,?,?,?,1,?)",
                    1L, "admin", audit[0], audit[1], audit[2], audit[3], "192.168.1.20",
                    "{\"demo\":true}", audit[4]);
        }

        String idCard = visIdCard.get("mading");
        insert("INSERT INTO blacklist (target_type, target_enc, target_hmac, target_mask, reason, valid_from, "
                        + "valid_to, status, created_by) VALUES (1,?,?,?,?,?,?,1,1)",
                crypto.encrypt(idCard), crypto.hmac(idCard), CryptoService.maskIdCard(idCard),
                "送货纠纷，暂停入厂 90 天", now.minusHours(6), now.plusDays(90));
    }

    private String deviceIp(String deviceKey) {
        List<String> rows = jdbc.query("SELECT ip FROM device WHERE id = ?",
                (rs, i) -> rs.getString(1), dev.get(deviceKey));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void notify(int receiverType, long receiverId, String template, String title, String content,
                        String bizNo, LocalDateTime sentAt, LocalDateTime readAt) {
        insert("INSERT INTO notification (receiver_type, receiver_id, channel, template_code, title, content, "
                        + "biz_type, biz_no, status, read_at, sent_at, dedup_key) "
                        + "VALUES (?,?,?,?,?,?,?,?,1,?,?,?)",
                receiverType, receiverId, Notification.CHANNEL_INAPP, template, title, content,
                "APPOINTMENT", bizNo, readAt, sentAt,
                template + ":" + bizNo + ":" + receiverType + "-" + receiverId);
    }

    // ==================== 基础工具 ====================

    private long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                Object value = args[i];
                if (value instanceof LocalDateTime ldt) {
                    ps.setTimestamp(i + 1, Timestamp.valueOf(ldt));
                } else if (value instanceof LocalDate ld) {
                    ps.setDate(i + 1, Date.valueOf(ld));
                } else {
                    ps.setObject(i + 1, value);
                }
            }
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0 : key.longValue();
    }

    private long queryLong(String sql, Object... args) {
        List<Long> rows = jdbc.query(sql, (rs, i) -> rs.getLong(1), args);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private Integer queryInt(String sql, Object... args) {
        List<Integer> rows = jdbc.query(sql, (rs, i) -> rs.getInt(1), args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("摘要计算失败", e);
        }
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}
