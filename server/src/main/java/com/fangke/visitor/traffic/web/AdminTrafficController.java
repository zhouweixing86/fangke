package com.fangke.visitor.traffic.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.common.PageResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        String where = where(from, to, personType, channel, keyword, args);

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
                               MAX(CASE WHEN t.channel = 'PEDESTRIAN' THEN 1 ELSE 0 END) AS has_pedestrian
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
                               t.event_id
                        FROM (
                            SELECT a.event_time, 'PEDESTRIAN' AS channel, a.direction,
                                   a.identify_type AS method_code, NULL AS plate_no,
                                   a.device_sn, a.gate_id, a.person_name, a.id AS event_id
                            FROM access_event a
                            WHERE a.person_no = ? AND a.event_time >= ? AND a.event_time < ?
                            UNION ALL
                            SELECT v.event_time, 'VEHICLE', v.direction, ?,
                                   v.plate_no, v.device_sn, v.gate_id, v.matched_name, NULL
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
        view.put("records", records);
        return ApiResponse.ok(view);
    }

    /* ---------------- 内部 ---------------- */

    private String where(LocalDate from, LocalDate to, String personType, String channel,
                         String keyword, List<Object> args) {
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
