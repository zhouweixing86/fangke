package com.fangke.visitor.vehicle.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 云车场开放接口签名（docs/19 §1）。
 *
 * <p>规则：非空参数按参数名 ASCII 升序拼 {@code k1=v1&k2=v2}，末尾追加 {@code &key=<secretKey>}，
 * 整串 MD5 后转 **大写**。{@code sign} 自身不参与；月卡接口的 {@code data} 数组也不参与。
 */
public final class CloudParkingSign {

    private CloudParkingSign() {
    }

    public static String sign(Map<String, ?> data, String secretKey) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("签名参数不能为空");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("secretKey 不能为空");
        }
        List<String> keys = new ArrayList<>(data.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if ("sign".equals(key) || "data".equals(key)) {
                continue;
            }
            Object value = data.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                continue;
            }
            sb.append(key).append('=').append(text).append('&');
        }
        sb.append("key=").append(secretKey.trim());
        return md5Upper(sb.toString());
    }

    public static String md5Upper(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
