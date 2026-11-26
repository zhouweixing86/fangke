package com.fangke.visitor.vehicle.mqtt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 车闸 MQTT 车牌下发状态（V36）：按「设备 + 车牌」记已下发指纹，用来差分下发与删除。 */
@Repository
public class VehicleMqttPlateStateRepository {

    private final JdbcTemplate jdbc;

    public VehicleMqttPlateStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 某台相机已下发的车牌 → 指纹。 */
    public Map<String, String> fingerprints(String deviceSn) {
        Map<String, String> view = new LinkedHashMap<>();
        jdbc.query("SELECT plate_no, fingerprint FROM vehicle_mqtt_plate_state WHERE device_sn = ?",
                rs -> {
                    view.put(rs.getString("plate_no"), rs.getString("fingerprint"));
                }, deviceSn);
        return view;
    }

    /**
     * 某台相机已下发的车牌 → 最近一次下发时间。
     *
     * <p>车牌名单里显示「什么时候推的」，用来判断是不是刚改完还没推下去。
     */
    public Map<String, LocalDateTime> pushedAt(String deviceSn) {
        Map<String, LocalDateTime> view = new LinkedHashMap<>();
        jdbc.query("SELECT plate_no, pushed_at FROM vehicle_mqtt_plate_state WHERE device_sn = ?",
                rs -> {
                    view.put(rs.getString("plate_no"), rs.getObject("pushed_at", LocalDateTime.class));
                }, deviceSn);
        return view;
    }

    public void upsert(String deviceSn, String plateNo, String fingerprint) {
        jdbc.update("""
                        INSERT INTO vehicle_mqtt_plate_state (device_sn, plate_no, fingerprint, pushed_at)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE fingerprint = VALUES(fingerprint), pushed_at = VALUES(pushed_at)
                        """,
                deviceSn, com.fangke.visitor.common.PlateNo.normalize(plateNo), fingerprint, LocalDateTime.now());
    }

    public void remove(String deviceSn, String plateNo) {
        jdbc.update("DELETE FROM vehicle_mqtt_plate_state WHERE device_sn = ? AND plate_no = ?",
                deviceSn, com.fangke.visitor.common.PlateNo.normalize(plateNo));
    }

    /** 设备档案删掉时清掉它的对账状态。 */
    public void removeDevice(String deviceSn) {
        jdbc.update("DELETE FROM vehicle_mqtt_plate_state WHERE device_sn = ?", deviceSn);
    }
}
