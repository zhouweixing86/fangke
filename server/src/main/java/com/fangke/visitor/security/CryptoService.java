package com.fangke.visitor.security;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 敏感字段加密（docs/10 §11）：AES-256-GCM 密文 + HMAC-SHA256 摘要 + 脱敏展示。
 *
 * <pre>
 * 密文格式：version(1B) || iv(12B) || ciphertext || tag(16B)
 * </pre>
 *
 * <p>HMAC 用于唯一索引与等值查询（加密后无法直接按明文查），密钥与 AES <b>分离</b>。
 */
@Component
public class CryptoService {

    private static final byte VERSION = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aesKey;
    private final byte[] hmacKey;

    public CryptoService(AppProperties props) {
        // 配置里给的是密钥材料；统一派生 32 字节，避免长度不合法的密钥直接报错
        this.aesKey = new SecretKeySpec(sha256(props.getCrypto().getAesKey()), "AES");
        this.hmacKey = sha256(props.getCrypto().getHmacKey());
    }

    public byte[] encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[1 + IV_LEN + cipherText.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV_LEN);
            System.arraycopy(cipherText, 0, out, 1 + IV_LEN, cipherText.length);
            return out;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "字段加密失败");
        }
    }

    public String decrypt(byte[] blob) {
        if (blob == null || blob.length <= 1 + IV_LEN) {
            return null;
        }
        try {
            byte version = blob[0];
            if (version != VERSION) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "不支持的密钥版本：" + version);
            }
            byte[] iv = Arrays.copyOfRange(blob, 1, 1 + IV_LEN);
            byte[] cipherText = Arrays.copyOfRange(blob, 1 + IV_LEN, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "字段解密失败");
        }
    }

    /** 十六进制 HMAC-SHA256，用于唯一索引与等值查询。 */
    public String hmac(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "HMAC 计算失败");
        }
    }

    /** 身份证脱敏：前 6 后 4（BR-D-008）。 */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.isBlank()) {
            return null;
        }
        String v = idCard.trim();
        if (v.length() <= 10) {
            return v;
        }
        return v.substring(0, 6) + "********" + v.substring(v.length() - 4);
    }

    /** 手机号脱敏：前 3 后 4。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String v = phone.trim();
        if (v.length() <= 7) {
            return v;
        }
        return v.substring(0, 3) + "****" + v.substring(v.length() - 4);
    }

    /** 仅用于内部校验/导出：把 base64 密文转回明文。 */
    public String decryptBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        return decrypt(Base64.getDecoder().decode(base64));
    }

    private static byte[] sha256(String material) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }
}
