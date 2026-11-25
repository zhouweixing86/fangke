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
                direction, deviceSn, topic, text, message);
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("SELECT id, direction, device_sn, topic, payload, message, created_at "
                + "FROM vehicle_mqtt_log ORDER BY id DESC LIMIT ?", Math.max(1, limit));
    }

    public void clear() {
        jdbc.update("DELETE FROM vehicle_mqtt_log");
    }
}
