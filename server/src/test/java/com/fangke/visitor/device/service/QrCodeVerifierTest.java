package com.fangke.visitor.device.service;

import com.fangke.visitor.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrCodeVerifierTest {

    private static final String TENANT = "T01";
    private static final String APPOINTMENT = "A2026111700001";
    private static final String NONCE = "8f3a1c";

    private QrCodeVerifier verifier;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getQr().setSecret("unit-test-secret");
        verifier = new QrCodeVerifier(props);
    }

    private String code(long exp) {
        String sig = verifier.sign(TENANT + "." + APPOINTMENT + "." + exp + "." + NONCE);
        return "RV1." + TENANT + "." + APPOINTMENT + "." + exp + "." + NONCE + "." + sig;
    }

    @Test
    @DisplayName("有效码：验签通过并解析出预约单号")
    void validCode() {
        long exp = Instant.now().getEpochSecond() + 60;
        QrCodeVerifier.Outcome outcome = verifier.verify(code(exp), Instant.now());

        assertTrue(outcome.valid());
        assertEquals(APPOINTMENT, outcome.appointmentNo());
    }

    @Test
    @DisplayName("过期码：返回 EXPIRED 而不是签名错误")
    void expiredCode() {
        long exp = Instant.now().getEpochSecond() - 1;
        QrCodeVerifier.Outcome outcome = verifier.verify(code(exp), Instant.now());

        assertEquals(QrCodeVerifier.Result.EXPIRED, outcome.result());
    }

    @Test
    @DisplayName("内容被篡改：签名不匹配")
    void tamperedCode() {
        long exp = Instant.now().getEpochSecond() + 60;
        String valid = code(exp);
        String tampered = valid.replace(APPOINTMENT, "A2026111700002");

        assertEquals(QrCodeVerifier.Result.BAD_SIGNATURE, verifier.verify(tampered, Instant.now()).result());
    }

    @Test
    @DisplayName("格式错误：段数不对或前缀不对")
    void invalidFormat() {
        assertEquals(QrCodeVerifier.Result.INVALID_FORMAT, verifier.verify("123456789012345", Instant.now()).result());
        assertEquals(QrCodeVerifier.Result.INVALID_FORMAT, verifier.verify("XX1.a.b.1.c.d", Instant.now()).result());
        assertEquals(QrCodeVerifier.Result.INVALID_FORMAT, verifier.verify(null, Instant.now()).result());
    }

    @Test
    @DisplayName("码长 ≥16 且非纯数字（设备硬约束）")
    void satisfiesDeviceConstraint() {
        String code = code(Instant.now().getEpochSecond() + 60);
        assertTrue(code.length() >= 16, code);
        assertFalse(code.chars().allMatch(Character::isDigit), code);
    }
}
