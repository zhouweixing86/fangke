package com.fangke.visitor.appointment.repository;

import com.fangke.visitor.appointment.model.Visitor;

import java.util.Optional;

public interface VisitorRepository {

    Optional<Visitor> findById(Long id);

    Optional<Visitor> findByIdCardHmac(String idCardHmac);

    Optional<Visitor> findByPhoneHmac(String phoneHmac);

    long insert(Visitor visitor);

    void update(Visitor visitor);

    /** 访客照片（V14）：只写人脸两列，避免覆盖其他字段。 */
    void updateFace(Long visitorId, String ossKey, int status);

    void recordConsent(Long visitorId, int consentType, String version, String ip, String ua);

    /** 当日最大访客编号（用于生成 V+yyyyMMdd+4位序号）。 */
    Optional<String> findMaxPersonNoByPrefix(String prefix);

    /** 微信小程序 openid 反查访客（一键登录）。 */
    Optional<Visitor> findByWxOpenid(String wxOpenid);

    /** 绑定 openid（首次预约成功后写入，后续一键登录直接命中）。 */
    void bindWxOpenid(Long visitorId, String wxOpenid);
}

