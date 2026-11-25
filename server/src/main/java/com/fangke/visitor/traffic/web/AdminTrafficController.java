package com.fangke.visitor.traffic.web;

import com.fangke.visitor.appointment.model.Appointment;
import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通行记录（C-69）：行人记录与车辆记录**合并**成一个页面。
 *
 * <p>列表口径：**按「人员编号 + 日期」分组**，一行 = 某人某天的进出汇总
 * （最早进入、最晚出去、进/出次数），点详情看当天全部明细。
 *
 * <p>两类原始记录分流：
 * <ul>
 *   <li>{@code access_event}：刷脸/刷证/扫码/人工 → 通行方式「行人」；</li>
 *   <li>{@code vehicle_event}：车牌识别 → 通行方式「车辆」。</li>
 * </ul>
 * 人员类型按 `person_no` 前缀判定（`E` = 员工，`V` = 访客），与编号生成规则一致。
 */
@RestController
@RequestMapping("/api/admin/traffic")
public class AdminTrafficController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 车牌识别在明细里的方法码（行人用 identify_type，见 docs/10）。 */
    private static final int METHOD_PLATE = 20;

    private static final String SUMMARY_FROM = """
            FROM (
                SELECT a.person_no AS person_no,
                       a.person_name AS person_name,
                       CASE WHEN a.person_no LIKE 'E%' THEN 'EMPLOYEE' ELSE 'VISITOR' END AS person_type,
                       DATE(a.event_time) AS day,
                       a.direction AS direction,
                       'PEDESTRIAN' AS channel,
                       a.event_time AS event_time
                FROM access_event a
                WHERE a.result_status = 1 AND a.person_no IS NOT NULL AND a.person_no <> ''
                UNION ALL
                SELECT v.person_no,
                       v.matched_name,
                       CASE WHEN v.matched_type = 1 THEN 'EMPLOYEE' ELSE 'VISITOR' END,
                       DATE(v.event_time),
                       v.direction,
                       'VEHICLE',
                       v.event_time
                FROM vehicle_event v
                WHERE v.result_status = 1 AND v.person_no IS NOT NULL AND v.person_no <> ''
            ) t
            LEFT JOIN employee emp ON t.person_type = 'EMPLOYEE' AND emp.person_no = t.person_no
            LEFT JOIN dept d ON d.id = emp.dept_id
            """;

    private final JdbcTemplate jdbc;

    public AdminTrafficController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 汇总列表：一人一天一行。
     *
     * @param personType EMPLOYEE / VISITOR，空表示全部
     * @param channel    PEDESTRIAN / VEHICLE，空表示全部
     */
    @GetMapping("/summary")
    public ApiResponse<PageResult<Map<String, Object>>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);

        List<Object> args = new ArrayList<>();
        String where = where(from, to, personType, channel, deptId, keyword, args);

        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM (SELECT 1 " + SUMMARY_FROM + where
                + " GROUP BY t.person_no, t.person_type, t.day) c", Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> list = jdbc.query("""
                        SELECT t.person_no, MAX(t.person_name) AS person_name, t.person_type, t.day,
                               MIN(CASE WHEN t.direction = 1 THEN t.event_time END) AS first_in,
                               MAX(CASE WHEN t.direction = 0 THEN t.event_time END) AS last_out,
                               SUM(CASE WHEN t.direction = 1 THEN 1 ELSE 0 END) AS in_count,
                               SUM(CASE WHEN t.direction = 0 THEN 1 ELSE 0 END) AS out_count,
                               COUNT(*) AS total_count,
                               MAX(CASE WHEN t.channel = 'VEHICLE' THEN 1 ELSE 0 END) AS has_vehicle,
                               MAX(CASE WHEN t.channel = 'PEDESTRIAN' THEN 1 ELSE 0 END) AS has_pedestrian,
                               MAX(d.dept_name) AS dept_name
                        """ + SUMMARY_FROM + where
                        + " GROUP BY t.person_no, t.person_type, t.day"
                        + " ORDER BY t.day DESC, first_in DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String personNo = rs.getString("person_no");
                    String type = rs.getString("person_type");
                    Timestamp firstIn = rs.getTimestamp("first_in");
                    Timestamp lastOut = rs.getTimestamp("last_out");
                    row.put("personNo", personNo);
                    row.put("personName", rs.getString("person_name") == null ? "" : rs.getString("person_name"));
                    row.put("personType", type);
                    row.put("personTypeText", "EMPLOYEE".equals(type) ? "员工" : "访客");
                    row.put("day", DATE.format(rs.getDate("day").toLocalDate()));
                    row.put("firstIn", firstIn == null ? "" : DATETIME.format(firstIn.toLocalDateTime()));
                    row.put("lastOut", lastOut == null ? "" : DATETIME.format(lastOut.toLocalDateTime()));
                    row.put("inCount", rs.getInt("in_count"));
                    row.put("outCount", rs.getInt("out_count"));
                    row.put("totalCount", rs.getInt("total_count"));
                    row.put("channelText", channelText(rs.getInt("has_vehicle") == 1,
                            rs.getInt("has_pedestrian") == 1));
                    row.put("deptName", rs.getString("dept_name") == null ? "" : rs.getString("dept_name"));
                    return row;
                },
                pageArgs.toArray());

        long count = total == null ? 0 : total;
        return ApiResponse.ok(PageResult.of(count, safePage, safeSize, list));
    }

    /** 某人某天的全部进出明细（行人 + 车辆），带通行方式与设备点位。 */
    @GetMapping("/{personNo}/detail")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable String personNo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<Map<String, Object>> records = jdbc.query("""
                        SELECT t.event_time, t.channel, t.direction, t.method_code, t.plate_no,
                               t.device_sn, d.device_name, t.gate_id, g.gate_name, t.person_name,
                               t.event_id, t.photo_url
                        FROM (
                            SELECT a.event_time, 'PEDESTRIAN' AS channel, a.direction,
                                   a.identify_type AS method_code, NULL AS plate_no,
                                   a.device_sn, a.gate_id, a.person_name, a.id AS event_id,
                                   NULL AS photo_url
                            FROM access_event a
                            WHERE a.person_no = ? AND a.event_time >= ? AND a.event_time < ?
                            UNION ALL
                            SELECT v.event_time, 'VEHICLE', v.direction, ?,
                                   v.plate_no, v.device_sn, v.gate_id, v.matched_name, NULL,
                                   v.photo_url
                            FROM vehicle_event v
                            WHERE v.person_no = ? AND v.event_time >= ? AND v.event_time < ?
                        ) t
                        LEFT JOIN device d ON d.device_sn = t.device_sn
                        LEFT JOIN gate g ON g.id = t.gate_id
                        ORDER BY t.event_time, t.channel
                        """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    int direction = rs.getInt("direction");
                    int method = rs.getInt("method_code");
                    String channel = rs.getString("channel");
                    row.put("personName", rs.getString("person_name") == null ? "" : rs.getString("person_name"));
                    row.put("eventTime", DATETIME.format(rs.getTimestamp("event_time").toLocalDateTime()));
                    row.put("channel", channel);
                    row.put("channelText", "VEHICLE".equals(channel) ? "车辆" : "行人");
                    row.put("direction", direction);
                    row.put("directionText", direction == 1 ? "进" : "出");
                    row.put("methodCode", method);
                    row.put("methodText", methodText(method, channel));
                    row.put("plateNo", rs.getString("plate_no") == null ? "" : rs.getString("plate_no"));
                    row.put("deviceSn", rs.getString("device_sn") == null ? "" : rs.getString("device_sn"));
                    row.put("deviceName", rs.getString("device_name") == null ? "" : rs.getString("device_name"));
                    row.put("gateName", rs.getString("gate_name") == null ? "" : rs.getString("gate_name"));
                    long eventId = rs.getLong("event_id");
                    row.put("eventId", rs.wasNull() ? null : eventId);
                    row.put("photoUrl", rs.getString("photo_url") == null ? "" : rs.getString("photo_url"));
                    return row;
                },
                personNo, Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()),
                METHOD_PLATE,
                personNo, Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("personNo", personNo);
        view.put("date", DATE.format(date));
        view.put("personName", records.stream()
                .map(r -> String.valueOf(r.get("personName")))
                .filter(name -> !name.isBlank())
                .findFirst()
                .orElse(""));
        view.putAll(personInfo(personNo, date));
        view.put("records", records);
        return ApiResponse.ok(view);
    }

    /* ---------------- 人员档案（详情用） ---------------- */

    /**
     * 详情头部信息：
     * <ul>
     *   <li>员工：部门、职务、工号、手机（脱敏）、车牌、证件有效期；</li>
     *   <li>访客：单位、手机（脱敏）、人脸采集状态 + **当天的申请信息与审批轨迹**。</li>
     * </ul>
     */
    private Map<String, Object> personInfo(String personNo, LocalDate date) {
        Map<String, Object> info = new LinkedHashMap<>();
        boolean employee = personNo != null && personNo.startsWith("E");
        info.put("personType", employee ? "EMPLOYEE" : "VISITOR");
        info.put("personTypeText", employee ? "员工" : "访客");
        if (employee) {
            jdbc.query("""
                            SELECT e.emp_no, e.title, e.phone_mask, e.plate_no, e.valid_from, e.valid_to,
                                   e.status, d.dept_name
                            FROM employee e
                            LEFT JOIN dept d ON d.id = e.dept_id
                            WHERE e.person_no = ? AND e.deleted_at IS NULL
                            LIMIT 1
                            """,
                    (rs, i) -> {
                        info.put("deptName", rs.getString("dept_name") == null ? "" : rs.getString("dept_name"));
                        info.put("empNo", rs.getString("emp_no") == null ? "" : rs.getString("emp_no"));
                        info.put("title", rs.getString("title") == null ? "" : rs.getString("title"));
                        info.put("phoneMask", rs.getString("phone_mask") == null ? "" : rs.getString("phone_mask"));
                        info.put("plateNo", rs.getString("plate_no") == null ? "" : rs.getString("plate_no"));
                        Date from = rs.getDate("valid_from");
                        Date to = rs.getDate("valid_to");
                        info.put("validFrom", from == null ? "" : DATE.format(from.toLocalDate()));
                        info.put("validTo", to == null ? "长期" : DATE.format(to.toLocalDate()));
                        info.put("statusText", switch (rs.getInt("status")) {
                            case 1 -> "在职";
                            case 2 -> "待复核";
                            case 3 -> "停用";
                            default -> "未知";
                        });
                        return info;
                    },
                    personNo);
            return info;
        }

        // 访客：档案 + 当天申请/审批
        List<Map<String, Object>> visitors = jdbc.query("""
                        SELECT id, company, phone_mask, face_status
                        FROM visitor WHERE person_no = ? LIMIT 1
                        """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("company", rs.getString("company") == null ? "" : rs.getString("company"));
                    row.put("phoneMask", rs.getString("phone_mask") == null ? "" : rs.getString("phone_mask"));
                    row.put("faceStatus", rs.getInt("face_status"));
                    return row;
                },
                personNo);
        if (visitors.isEmpty()) {
            info.put("appointments", List.of());
            return info;
        }
        Map<String, Object> visitor = visitors.get(0);
        info.put("company", visitor.get("company"));
        info.put("phoneMask", visitor.get("phoneMask"));
        info.put("faceStatus", visitor.get("faceStatus"));
        info.put("appointments", appointments((Long) visitor.get("id"), date));
        return info;
    }

    /** 访客当天的预约单 + 审批轨迹（谁确认、谁复核、意见、门点）。 */
    private List<Map<String, Object>> appointments(Long visitorId, LocalDate date) {
        List<Map<String, Object>> list = jdbc.query("""
                        SELECT a.id, a.appointment_no, a.status, a.purpose, a.visitor_type, a.plate_no,
                               a.start_time, a.end_time, a.created_at, emp.name AS visit_employee_name,
                               (SELECT GROUP_CONCAT(gt.gate_name ORDER BY gt.sort_no, gt.id SEPARATOR '/')
                                  FROM appointment_gate ag JOIN gate gt ON gt.id = ag.gate_id
                                 WHERE ag.appointment_id = a.id AND ag.stage = 2) AS approved_gates
                        FROM appointment a
                        LEFT JOIN employee emp ON emp.id = a.visit_employee_id
                        WHERE a.visitor_id = ? AND a.start_time >= ? AND a.start_time < ?
                        ORDER BY a.id DESC
                        """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long id = rs.getLong("id");
                    int status = rs.getInt("status");
                    row.put("id", id);
                    row.put("appointmentNo", rs.getString("appointment_no"));
                    row.put("status", status);
                    row.put("statusText", Appointment.statusText(status));
                    row.put("purpose", rs.getString("purpose") == null ? "" : rs.getString("purpose"));
                    row.put("visitorType", rs.getString("visitor_type") == null ? "" : rs.getString("visitor_type"));
                    row.put("plateNo", rs.getString("plate_no") == null ? "" : rs.getString("plate_no"));
                    row.put("visitEmployeeName",
                            rs.getString("visit_employee_name") == null ? "" : rs.getString("visit_employee_name"));
                    row.put("approvedGates", rs.getString("approved_gates") == null
                            ? "" : rs.getString("approved_gates"));
                    row.put("startTime", DATETIME.format(rs.getTimestamp("start_time").toLocalDateTime()));
                    row.put("endTime", DATETIME.format(rs.getTimestamp("end_time").toLocalDateTime()));
                    row.put("createdAt", rs.getTimestamp("created_at") == null
                            ? "" : DATETIME.format(rs.getTimestamp("created_at").toLocalDateTime()));
                    return row;
                },
                visitorId, Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()));

        for (Map<String, Object> row : list) {
            row.put("actions", actions((Long) row.get("id")));
        }
        return list;
    }

    /** 审批轨迹：一级（被访人确认）与二级（门卫复核）都在 approval_action 里。 */
    private List<Map<String, Object>> actions(Long appointmentId) {
        return jdbc.query("""
                        SELECT aa.action, aa.operator_type, aa.comment, aa.gate_snapshot, aa.created_at,
                               COALESCE(e.name, u.real_name, '') AS operator_name
                        FROM approval_action aa
                        LEFT JOIN employee e ON aa.operator_type = 1 AND e.id = aa.operator_id
                        LEFT JOIN sys_user u ON aa.operator_type = 2 AND u.id = aa.operator_id
                        WHERE aa.appointment_id = ?
                        ORDER BY aa.id
                        """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    int action = rs.getInt("action");
                    int operatorType = rs.getInt("operator_type");
                    row.put("action", action);
                    row.put("actionText", actionText(action));
                    row.put("operatorType", operatorType);
                    row.put("operatorRole", operatorType == 2 ? "门卫" : "被访人");
                    row.put("operatorName", rs.getString("operator_name") == null ? "" : rs.getString("operator_name"));
                    row.put("comment", rs.getString("comment") == null ? "" : rs.getString("comment"));
                    row.put("gates", gateNames(rs.getString("gate_snapshot")));
                    row.put("at", rs.getTimestamp("created_at") == null
                            ? "" : DATETIME.format(rs.getTimestamp("created_at").toLocalDateTime()));
                    return row;
                },
                appointmentId);
    }

    private String actionText(int action) {
        return switch (action) {
            case 1 -> "通过";
            case 2 -> "驳回";
            case 3 -> "转办";
            case 4 -> "加签";
            case 5 -> "催办";
            case 6 -> "系统跳过";
            default -> "未知";
        };
    }

    /** 审批动作里存的门点 ID 快照 → 门点名。 */
    private List<String> gateNames(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String id : snapshot.split(",")) {
            String trimmed = id.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                jdbc.query("SELECT gate_name FROM gate WHERE id = ?",
                        (rs, i) -> names.add(rs.getString(1)), Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                // 非法快照直接跳过，不影响详情
            }
        }
        return names;
    }

    /* ---------------- 内部 ---------------- */

    private String where(LocalDate from, LocalDate to, String personType, String channel,
                         Long deptId, String keyword, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (from != null) {
            sql.append(" AND t.day >= ?");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND t.day <= ?");
            args.add(java.sql.Date.valueOf(to));
        }
        if (personType != null && !personType.isBlank()) {
            sql.append(" AND t.person_type = ?");
            args.add(personType.trim().toUpperCase());
        }
        if (channel != null && !channel.isBlank()) {
            sql.append(" AND t.channel = ?");
            args.add(channel.trim().toUpperCase());
        }
        if (deptId != null) {
            sql.append(" AND d.id = ?");
            args.add(deptId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (t.person_name LIKE ? OR t.person_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        return sql.toString();
    }

    /** 通道文案：某人当天走了哪些通道。 */
    private String channelText(boolean vehicle, boolean pedestrian) {
        if (vehicle && pedestrian) {
            return "车辆+行人";
        }
        return vehicle ? "车辆" : "行人";
    }

    /** 识别方式文案；车牌识别单独一类。 */
    private String methodText(int method, String channel) {
        if ("VEHICLE".equals(channel)) {
            return "车牌识别";
        }
        return switch (method) {
            case 0 -> "人脸";
            case 2 -> "身份证";
            case 3 -> "IC 卡";
            case 5 -> "二维码";
            case 6 -> "密码";
            case 9 -> "人工开门";
            default -> "其他";
        };
    }
}
