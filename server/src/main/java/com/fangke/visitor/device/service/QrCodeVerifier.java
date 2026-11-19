package com.fangke.visitor.device.service;

import com.fangke.visitor.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * 动态二维码验签（docs/11 §2.4）。
 *
 * <p>码格式：{@code RV1.{tenantCode}.{appointmentNo}.{exp}.{nonce}.{hmac8}}
 * <ul>
 *   <li>签名输入：{@code tenantCode.appointmentNo.exp.nonce}；</li>
 *   <li>{@code hmac8} 取 HMAC-SHA256 十六进制前 8 个字符；</li>
 *   <li>长度必然 ≥ 16 且非纯数字（docs/06 §0.3 硬约束）。</li>
 * </ul>
 */
@Component
public class QrCodeVerifier {

    public enum Result { VALID, INVALID_FORMAT, BAD_SIGNATURE, EXPIRED }

    /**
     * @param result        校验结果
     * @param appointmentNo 预约单号（格式正确时才有值）
     * @param expireAt      码的过期时间（格式正确时才有值）
     */
    public record Outcome(Result result, String appointmentNo, Instant expireAt) {

        public boolean valid() {
            return result == Result.VALID;
        }
    }

    private final byte[] secret;

    public QrCodeVerifier(AppProperties props) {
        this.secret = props.getQr().getSecret().getBytes(StandardCharsets.UTF_8);
    }

    public Outcome verify(String code, Instant now) {
        if (code == null || code.isBlank()) {
            return new Outcome(Result.INVALID_FORMAT, null, null);
        }
        String[] parts = code.trim().split("\\.");
        if (parts.length != 6 || !"RV1".equals(parts[0])) {
            return new Outcome(Result.INVALID_FORMAT, null, null);
        }
        String tenant = parts[1];
        String appointmentNo = parts[2];
        long exp;
        try {
            exp = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return new Outcome(Result.INVALID_FORMAT, appointmentNo, null);
        }
        String nonce = parts[4];
        String signature = parts[5];

        String expected = sign(tenant + "." + appointmentNo + "." + exp + "." + nonce);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return new Outcome(Result.BAD_SIGNATURE, appointmentNo, Instant.ofEpochSecond(exp));
        }
        Instant expireAt = Instant.ofEpochSecond(exp);
        if (now.isAfter(expireAt)) {
            return new Outcome(Result.EXPIRED, appointmentNo, expireAt);
        }
        return new Outcome(Result.VALID, appointmentNo, expireAt);
    }

    /** 供签发端复用，保证签验一致。 */
    public String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("二维码签名失败", e);
        }
    }
}
