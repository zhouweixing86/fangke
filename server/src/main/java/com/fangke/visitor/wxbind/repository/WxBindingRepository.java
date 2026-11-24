package com.fangke.visitor.wxbind.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 微信角色绑定仓储（V29）：角色二维码 + 微信账号 + 已授予角色。
 */
@Repository
public class WxBindingRepository {

    /** 管理端二维码列表行（含已绑定人数）。 */
    public record RoleQrRow(long id, long roleId, String roleCode, String roleName, String token,
                            int status, int bindCount) {
    }

    /** 扫码预览：二维码对应的角色。 */
    public record QrRole(long id, long roleId, String roleCode, String roleName, String token) {
    }

    /** 微信账号绑定档案。 */
    public record Binding(long id, String openid, String name, String phoneMask, Long employeeId,
                          List<String> roleCodes) {
    }

    private static final RowMapper<Binding> BINDING_MAPPER = (rs, i) -> new Binding(
            rs.getLong("id"),
            rs.getString("openid"),
            rs.getString("name"),
            rs.getString("phone_mask"),
            rs.getObject("employee_id") == null ? null : rs.getLong("employee_id"),
            List.of());

    private final JdbcTemplate jdbc;

    public WxBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 启用中的后台角色 + 二维码（没有二维码的角色 qrId=0、token=null）。 */
    public List<RoleQrRow> listRolesWithQr() {
        return jdbc.query("""
                        SELECT r.id AS role_id, r.role_code, r.role_name,
                               q.id AS qr_id, q.token, COALESCE(q.status, 0) AS qr_status,
                               (SELECT COUNT(1) FROM wx_binding_role br WHERE br.role_id = r.id) AS bind_count
                        FROM sys_role r
                        LEFT JOIN wx_role_qr q ON q.role_id = r.id
                        WHERE r.status = 1
                        ORDER BY r.sort, r.id
                        """,
                (rs, i) -> new RoleQrRow(
                        rs.getObject("qr_id") == null ? 0L : rs.getLong("qr_id"),
                        rs.getLong("role_id"),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getString("token"),
                        rs.getInt("qr_status"),
                        rs.getInt("bind_count")));
    }

    public Optional<QrRole> findByToken(String token) {
        return jdbc.query("""
                        SELECT q.id, q.role_id, q.role_code, r.role_name, q.token
                        FROM wx_role_qr q JOIN sys_role r ON r.id = q.role_id
                        WHERE q.token = ? AND q.status = 1 AND r.status = 1
                        """,
                (rs, i) -> new QrRole(rs.getLong("id"), rs.getLong("role_id"), rs.getString("role_code"),
                        rs.getString("role_name"), rs.getString("token")),
                token).stream().findFirst();
    }

    public Optional<String> findRoleCode(long roleId) {
        return jdbc.query("SELECT role_code FROM sys_role WHERE id = ? AND status = 1",
                (rs, i) -> rs.getString(1), roleId).stream().findFirst();
    }

    /** 生成/更新某角色的二维码 token（一个角色一条）。 */
    public void saveToken(long roleId, String roleCode, String token, Long operatorId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM wx_role_qr WHERE role_id = ?",
                Integer.class, roleId);
        if (count != null && count > 0) {
            jdbc.update("UPDATE wx_role_qr SET token = ?, role_code = ?, status = 1 WHERE role_id = ?",
                    token, roleCode, roleId);
            return;
        }
        jdbc.update("""
                        INSERT INTO wx_role_qr (role_id, role_code, token, status, created_by)
                        VALUES (?, ?, ?, 1, ?)
                        """, roleId, roleCode, token, operatorId);
    }

    public Optional<Binding> findByOpenid(String openid) {
        return jdbc.query("SELECT * FROM wx_binding WHERE openid = ? AND status = 1",
                        BINDING_MAPPER, openid).stream().findFirst()
                .map(b -> new Binding(b.id(), b.openid(), b.name(), b.phoneMask(), b.employeeId(),
                        roleCodes(b.id())));
    }

    public Optional<Binding> findById(long id) {
        return jdbc.query("SELECT * FROM wx_binding WHERE id = ? AND status = 1",
                        BINDING_MAPPER, id).stream().findFirst()
                .map(b -> new Binding(b.id(), b.openid(), b.name(), b.phoneMask(), b.employeeId(),
                        roleCodes(b.id())));
    }

    public Optional<Binding> findByPhoneHmac(String phoneHmac) {
        return jdbc.query("SELECT * FROM wx_binding WHERE phone_hmac = ? AND status = 1",
                        BINDING_MAPPER, phoneHmac).stream().findFirst()
                .map(b -> new Binding(b.id(), b.openid(), b.name(), b.phoneMask(), b.employeeId(),
                        roleCodes(b.id())));
    }

    public List<String> roleCodes(long bindingId) {
        return jdbc.query("SELECT role_code FROM wx_binding_role WHERE binding_id = ? ORDER BY id",
                (rs, i) -> rs.getString(1), bindingId);
    }

    /** 新增或更新微信绑定（openid 唯一），返回 binding id。 */
    public long upsertBinding(String openid, String name, byte[] phoneEnc, String phoneMask,
                              String phoneHmac, Long employeeId) {
        Optional<Binding> existing = jdbc.query("SELECT * FROM wx_binding WHERE openid = ?",
                BINDING_MAPPER, openid).stream().findFirst();
        if (existing.isPresent()) {
            long id = existing.get().id();
            jdbc.update("""
                            UPDATE wx_binding SET name = ?, phone_enc = ?, phone_mask = ?, phone_hmac = ?,
                                employee_id = ?, status = 1
                            WHERE id = ?
                            """, name, phoneEnc, phoneMask, phoneHmac, employeeId, id);
            return id;
        }
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO wx_binding (openid, name, phone_enc, phone_mask, phone_hmac, employee_id, status)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, openid);
            ps.setString(2, name);
            ps.setBytes(3, phoneEnc);
            ps.setString(4, phoneMask);
            ps.setString(5, phoneHmac);
            if (employeeId == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, employeeId);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("微信绑定写入未返回主键");
        }
        return key.longValue();
    }

    /** 授予角色（重复扫码幂等）。 */
    public void addRole(long bindingId, long roleId, String roleCode, Long qrId) {
        jdbc.update("""
                        INSERT INTO wx_binding_role (binding_id, role_id, role_code, source_qr)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE role_code = VALUES(role_code)
                        """, bindingId, roleId, roleCode, qrId);
    }

    public long countBindingsByRole(long roleId) {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM wx_binding_role WHERE role_id = ?",
                Long.class, roleId);
        return count == null ? 0 : count;
    }

    /* ---------------- 管理端：微信身份列表与撤销（C-77） ---------------- */

    /** 绑定 + 角色汇总行。 */
    public record BindingRow(long id, String openid, String name, String phoneMask, Long employeeId,
                             String roleCodes, String createdAt) {
    }

    public List<BindingRow> listBindings(String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT b.id, b.openid, b.name, b.phone_mask, b.employee_id, b.created_at,
                       (SELECT GROUP_CONCAT(br.role_code ORDER BY br.id) FROM wx_binding_role br
                         WHERE br.binding_id = b.id) AS role_codes
                FROM wx_binding b
                WHERE b.status = 1
                """);
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (b.name LIKE ? OR b.phone_mask LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY b.id DESC LIMIT 500");
        return jdbc.query(sql.toString(), (rs, i) -> new BindingRow(
                rs.getLong("id"),
                rs.getString("openid"),
                rs.getString("name"),
                rs.getString("phone_mask"),
                rs.getObject("employee_id") == null ? null : rs.getLong("employee_id"),
                rs.getString("role_codes"),
                rs.getTimestamp("created_at").toLocalDateTime().toString().replace('T', ' ')), args.toArray());
    }

    /** 撤销某人的某一个角色（该微信失去该角色，其余角色保留）。 */
    public int deleteRole(long bindingId, String roleCode) {
        return jdbc.update("DELETE FROM wx_binding_role WHERE binding_id = ? AND role_code = ?",
                bindingId, roleCode);
    }

    /** 彻底解绑：该微信失去全部身份（下次登录回到普通访客）。 */
    public void deleteBinding(long bindingId) {
        jdbc.update("DELETE FROM wx_binding_role WHERE binding_id = ?", bindingId);
        jdbc.update("DELETE FROM wx_binding WHERE id = ?", bindingId);
    }
}
