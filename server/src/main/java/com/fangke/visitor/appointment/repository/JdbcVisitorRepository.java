package com.fangke.visitor.appointment.repository;

import com.fangke.visitor.appointment.model.Visitor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcVisitorRepository implements VisitorRepository {

    private static final String SELECT = """
            SELECT id, person_no, name, phone_enc, phone_hmac, phone_mask, id_card_enc, id_card_hmac, id_card_mask,
                   company, face_oss_key, face_status, first_visit_at, last_visit_at, visit_count, is_blacklist
            FROM visitor
            """;

    private static final RowMapper<Visitor> MAPPER = new RowMapper<>() {
        @Override
        public Visitor mapRow(ResultSet rs, int rowNum) throws SQLException {
            Visitor v = new Visitor();
            v.setId(rs.getLong("id"));
            v.setPersonNo(rs.getString("person_no"));
            v.setName(rs.getString("name"));
            v.setPhoneEnc(rs.getBytes("phone_enc"));
            v.setPhoneHmac(rs.getString("phone_hmac"));
            v.setPhoneMask(rs.getString("phone_mask"));
            v.setIdCardEnc(rs.getBytes("id_card_enc"));
            v.setIdCardHmac(rs.getString("id_card_hmac"));
            v.setIdCardMask(rs.getString("id_card_mask"));
            v.setCompany(rs.getString("company"));
            v.setFaceOssKey(rs.getString("face_oss_key"));
            v.setFaceStatus(rs.getInt("face_status"));
            if (rs.getTimestamp("first_visit_at") != null) {
                v.setFirstVisitAt(rs.getTimestamp("first_visit_at").toLocalDateTime());
            }
            if (rs.getTimestamp("last_visit_at") != null) {
                v.setLastVisitAt(rs.getTimestamp("last_visit_at").toLocalDateTime());
            }
            v.setVisitCount(rs.getInt("visit_count"));
            v.setBlacklist(rs.getInt("is_blacklist") == 1);
            return v;
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcVisitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Visitor> findById(Long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Visitor> findByIdCardHmac(String idCardHmac) {
        return jdbc.query(SELECT + " WHERE id_card_hmac = ?", MAPPER, idCardHmac).stream().findFirst();
    }

    @Override
    public Optional<Visitor> findByPhoneHmac(String phoneHmac) {
        return jdbc.query(SELECT + " WHERE phone_hmac = ? ORDER BY id DESC LIMIT 1", MAPPER, phoneHmac)
                .stream().findFirst();
    }

    @Override
    public long insert(Visitor v) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO visitor (person_no, name, phone_enc, phone_hmac, phone_mask,
                        id_card_enc, id_card_hmac, id_card_mask, company, visit_count, is_blacklist)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, v.getPersonNo());
            ps.setString(2, v.getName());
            setNullableBytes(ps, 3, v.getPhoneEnc());
            ps.setString(4, v.getPhoneHmac());
            ps.setString(5, v.getPhoneMask());
            ps.setBytes(6, v.getIdCardEnc());
            ps.setString(7, v.getIdCardHmac());
            ps.setString(8, v.getIdCardMask());
            ps.setString(9, v.getCompany());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("访客写入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void update(Visitor v) {
        jdbc.update("""
                        UPDATE visitor SET name = ?, phone_enc = ?, phone_hmac = ?, phone_mask = ?,
                            company = ?, last_visit_at = ?, visit_count = ?
                        WHERE id = ?
                        """,
                v.getName(), v.getPhoneEnc(), v.getPhoneHmac(), v.getPhoneMask(),
                v.getCompany(),
                v.getLastVisitAt() == null ? null : Timestamp.valueOf(v.getLastVisitAt()),
                v.getVisitCount(), v.getId());
    }

    @Override
    public void updateFace(Long visitorId, String ossKey, int status) {
        jdbc.update("UPDATE visitor SET face_oss_key = ?, face_status = ? WHERE id = ?",
                ossKey, status, visitorId);
    }

    @Override
    public void recordConsent(Long visitorId, int consentType, String version, String ip, String ua) {
        jdbc.update("INSERT INTO visitor_consent (visitor_id, consent_type, version, agreed_at, ip, ua) "
                        + "VALUES (?, ?, ?, NOW(3), ?, ?)",
                visitorId, consentType, version, ip, ua);
    }

    @Override
    public Optional<String> findMaxPersonNoByPrefix(String prefix) {
        List<String> list = jdbc.query("SELECT MAX(person_no) FROM visitor WHERE person_no LIKE ?",
                (rs, i) -> rs.getString(1), prefix + "%");
        return list.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public Optional<Visitor> findByWxOpenid(String wxOpenid) {
        if (wxOpenid == null || wxOpenid.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " WHERE wx_openid = ? LIMIT 1", MAPPER, wxOpenid).stream().findFirst();
    }

    @Override
    public void bindWxOpenid(Long visitorId, String wxOpenid) {
        if (visitorId == null || wxOpenid == null || wxOpenid.isBlank()) {
            return;
        }
        // 一个 openid 只绑一个访客：先解绑可能存在的旧记录，避免唯一键冲突
        jdbc.update("UPDATE visitor SET wx_openid = NULL WHERE wx_openid = ? AND id <> ?",
                wxOpenid, visitorId);
        jdbc.update("UPDATE visitor SET wx_openid = ? WHERE id = ?", wxOpenid, visitorId);
    }

    private void setNullableBytes(PreparedStatement ps, int index, byte[] value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARBINARY);
        } else {
            ps.setBytes(index, value);
        }
    }
}
