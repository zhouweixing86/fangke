package com.fangke.visitor.vehicle.mqtt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 车闸 MQTT 报文日志（{@code vehicle_mqtt_log}）：上下行都留痕，现场排障/协议探测用。
 */
@Repository
public class VehicleMqttLogRepository {

    /** 单条报文最多存多少字符（大图 base64 会截断，避免撑爆数据库）。 */
    private static final int MAX_PAYLOAD = 20_000;

    private final JdbcTemplate jdbc;

    public VehicleMqttLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String direction, String deviceSn, String topic, String payload, String message) {
        String text = payload;
        if (text != null && text.length() > MAX_PAYLOAD) {
            text = text.substring(0, MAX_PAYLOAD) + "...(截断)";
        }
        jdbc.update("INSERT INTO vehicle_mqtt_log (direction, device_sn, topic, payload, message) VALUES (?,?,?,?,?)",
                direction, cut(deviceSn, 64), cut(topic, 128), text, cut(message, 255));
    }

    /** 按列宽截断：日志表也不能因为一个超长 message 把整条记录写失败。 */
    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("SELECT id, direction, device_sn, topic, payload, message, created_at "
                + "FROM vehicle_mqtt_log ORDER BY id DESC LIMIT ?", Math.max(1, limit));
    }

    /**
     * 最近一次「相机拒绝下行」回执（{@code errCode != 0}）。
     *
     * <p>页面上直接提示用：协议回执不带车牌，只能定位到设备 + 时间 + 原因。
     *
     * @param deviceSn 为空表示全部设备
     * @return 无失败记录时返回 {@code null}
     */
    public Map<String, Object> lastFailure(String deviceSn) {
        return last("下行回执：失败%", deviceSn);
    }

    /** 最近一次下行回执（成功或失败都算）。 */
    public Map<String, Object> lastReply(String deviceSn) {
        return last("下行回执：%", deviceSn);
    }

    private Map<String, Object> last(String messagePattern, String deviceSn) {
        String sql = "SELECT id, device_sn, message, created_at FROM vehicle_mqtt_log "
                + "WHERE direction = 'UP' AND message LIKE ? "
                + (deviceSn == null || deviceSn.isBlank() ? "" : "AND device_sn = ? ")
                + "ORDER BY id DESC LIMIT 1";
        List<Map<String, Object>> rows = deviceSn == null || deviceSn.isBlank()
                ? jdbc.queryForList(sql, messagePattern)
                : jdbc.queryForList(sql, messagePattern, deviceSn.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void clear() {
        jdbc.update("DELETE FROM vehicle_mqtt_log");
    }
}
