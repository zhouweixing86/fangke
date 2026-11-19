package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcDeviceRepository implements DeviceRepository {

    private static final RowMapper<Device> MAPPER = new RowMapper<>() {
        @Override
        public Device mapRow(ResultSet rs, int rowNum) throws SQLException {
            Device d = new Device();
            d.setId(rs.getLong("id"));
            d.setDeviceSn(rs.getString("device_sn"));
            d.setMac(rs.getString("mac"));
            d.setDeviceName(rs.getString("device_name"));
            d.setModel(rs.getString("model"));
            d.setFirmware(rs.getString("firmware"));
            d.setGateId(rs.getLong("gate_id"));
            d.setDirection(rs.getInt("direction"));
            d.setChannel(parseChannel(rs.getString("channel")));
            d.setIp(rs.getString("ip"));
            d.setPort(rs.getInt("port"));
            byte[] pwd = rs.getBytes("api_password");
            d.setApiPassword(pwd == null ? null : new String(pwd, StandardCharsets.UTF_8));
            d.setOnline(rs.getInt("online_state") == 1);
            LocalDateTime heartbeat = rs.getTimestamp("last_heartbeat") == null
                    ? null : rs.getTimestamp("last_heartbeat").toLocalDateTime();
            d.setLastHeartbeat(heartbeat);
            int count = rs.getInt("person_count");
            d.setPersonCount(rs.wasNull() ? null : count);
            int capacity = rs.getInt("capacity");
            d.setCapacity(rs.wasNull() ? null : capacity);
            d.setStatus(rs.getInt("status"));
            d.setCapabilityJson(rs.getString("capability"));
            d.setRemark(rs.getString("remark"));
            return d;
        }

        /** 通道值以库为准；新通道名还没发版时降级为 LAN，避免整表查询直接抛异常。 */
        private static Device.Channel parseChannel(String raw) {
            if (raw == null || raw.isBlank()) {
                return Device.Channel.LAN;
            }
            try {
                return Device.Channel.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Device.Channel.LAN;
            }
        }
    };

    private static final String SELECT = "SELECT id, device_sn, mac, device_name, model, firmware, gate_id, "
            + "direction, channel, ip, port, api_password, capability, online_state, last_heartbeat, "
            + "person_count, capacity, status, remark "
            + "FROM device ";

    private final JdbcTemplate jdbc;

    public JdbcDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Device> findBySn(String deviceSn) {
        List<Device> list = jdbc.query(SELECT + "WHERE device_sn = ? LIMIT 1", MAPPER, deviceSn);
        return list.stream().findFirst();
    }

    @Override
    public Optional<Device> findById(Long id) {
        List<Device> list = jdbc.query(SELECT + "WHERE id = ? LIMIT 1", MAPPER, id);
        return list.stream().findFirst();
    }

    @Override
    public Optional<Device> findByMac(String mac) {
        List<Device> list = jdbc.query(SELECT + "WHERE mac = ? LIMIT 1", MAPPER, mac);
        return list.stream().findFirst();
    }

    @Override
    public List<Device> findActiveByGate(Long gateId) {
        return jdbc.query(SELECT + "WHERE gate_id = ? AND status = 1 ORDER BY direction DESC, id", MAPPER, gateId);
    }

    @Override
    public List<Device> findAllActive() {
        return jdbc.query(SELECT + "WHERE status = 1 ORDER BY id", MAPPER);
    }

    @Override
    public void updateHeartbeat(String deviceSn, String ip, Integer personCount,
                                LocalDateTime heartbeatAt, Integer clockSkewSeconds, String rawJson) {
        jdbc.update("UPDATE device SET online_state = 1, last_heartbeat = ?, person_count = COALESCE(?, person_count), "
                        + "ip = COALESCE(?, ip) WHERE device_sn = ?",
                heartbeatAt, personCount, ip, deviceSn);

        // 样本写入 device_heartbeat：保留策略由 JOB 清理（docs/10 §12），此处不判断抽样
        jdbc.update("INSERT INTO device_heartbeat (device_id, device_sn, device_ip, person_count, device_time, clock_skew_s, raw) "
                        + "SELECT id, ?, ?, ?, ?, ?, ? FROM device WHERE device_sn = ?",
                deviceSn, ip, personCount, heartbeatAt, clockSkewSeconds, rawJson, deviceSn);
    }

    @Override
    public void updateFirmware(String deviceSn, String firmware, String model) {
        jdbc.update("UPDATE device SET firmware = COALESCE(?, firmware), model = COALESCE(?, model) WHERE device_sn = ?",
                firmware, model, deviceSn);
    }

    @Override
    public long register(Device device) {
        // 不再默认落到 1 号门：多门点场景下误落会导致名单下发到错误的门。
        if (device.getGateId() == null) {
            throw new IllegalStateException("设备登记必须指定门点");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO device (device_sn, mac, device_name, model, firmware, gate_id, direction,
                        channel, ip, port, api_password, online_state, status, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, device.getDeviceSn());
            ps.setString(2, device.getMac());
            ps.setString(3, device.getDeviceName());
            ps.setString(4, device.getModel());
            ps.setString(5, device.getFirmware());
            ps.setLong(6, device.getGateId());
            ps.setInt(7, device.getDirection() == null ? 1 : device.getDirection());
            ps.setString(8, device.getChannel() == null ? Device.Channel.PULL.name() : device.getChannel().name());
            ps.setString(9, device.getIp());
            ps.setInt(10, device.getPort());
            if (device.getApiPassword() == null) {
                ps.setNull(11, java.sql.Types.VARBINARY);
            } else {
                ps.setBytes(11, device.getApiPassword().getBytes(StandardCharsets.UTF_8));
            }
            ps.setInt(12, device.getStatus() == 0 ? 0 : 1);
            ps.setString(13, "轮询式终端首次心跳自动登记");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("设备登记未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void bindMac(Long deviceId, String mac) {
        jdbc.update("UPDATE device SET mac = ? WHERE id = ? AND mac LIKE 'PULL-%'", mac, deviceId);
    }

    @Override
    public void updateMeta(Long deviceId, String deviceName, String model, Integer direction,
                           Long gateId, String remark) {
        jdbc.update("UPDATE device SET device_name = COALESCE(?, device_name), model = COALESCE(?, model), "
                        + "direction = COALESCE(?, direction), gate_id = COALESCE(?, gate_id), "
                        + "remark = COALESCE(?, remark) WHERE id = ?",
                deviceName, model, direction, gateId, remark, deviceId);
    }

    @Override
    public int countByGate(Long gateId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device WHERE gate_id = ? AND status = 1", Integer.class, gateId);
        return count == null ? 0 : count;
    }
}
