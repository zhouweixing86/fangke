package com.fangke.visitor.device.pull;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 新终端 HTTP 协议的签名（协议文档 §2.3）。
 *
 * <pre>
 * sign = MD5( key1=value1&amp;key2=value2…&amp;key=平台key ).toUpperCase()
 * </pre>
 *
 * 规则细节（从文档推得，真机到位后需二次确认）：
 * <ul>
 *   <li>{@code data} **不参与**签名；{@code sign} 本身也不参与；</li>
 *   <li>字段按字段名 ASCII 升序；值用原始值，**不做 URLEncode**；</li>
 *   <li>末尾追加 {@code &key=<平台key>}；</li>
 *   <li>MD5 后转大写。</li>
 * </ul>
 * 文档示例里 `ver =v1.0` 带空格、`driverno` 疑似笔误，实现按上面通用规则，
 * 并在校验失败时打印实际拼接串，便于真机联调时比对。
 */
public final class OpenApiSign {

    private OpenApiSign() {
    }

    /** 计算签名。 */
    public static String sign(Map<String, String> params, String key) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            params.forEach((k, v) -> {
                if (k == null || k.isBlank() || "data".equals(k) || "sign".equals(k)) {
                    return;
                }
                if (v == null) {
                    return;
                }
                sorted.put(k, v);
            });
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        sb.append("&key=").append(key == null ? "" : key);
        return md5Upper(sb.toString());
    }

    /**
     * 从原始请求体校验签名。
     *
     * @return true 表示签名一致；{@code body} 缺 sign 时返回 false
     */
    public static boolean verify(JsonNode body, String key) {
        String expected = expectedSign(body, key);
        if (expected == null) {
            return false;
        }
        JsonNode signNode = body.get("sign");
        if (signNode == null || signNode.isNull()) {
            return false;
        }
        String actual = signNode.asText("").trim().toUpperCase();
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 便于日志排查：算出期望签名（不返回密钥本身）。 */
    public static String expectedSign(JsonNode body, String key) {
        if (body == null || !body.isObject()) {
            return null;
        }
        TreeMap<String, String> flat = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = body.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            if ("data".equals(name) || "sign".equals(name)) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            // 对象/数组顶层字段极少见；按 JSON 串参与，保证可重复
            flat.put(name, value.isValueNode() ? value.asText() : value.toString());
        }
        return sign(flat, key);
    }

    /** 参与签名的字段（用于日志与测试）。 */
    public static List<String> signedFields(JsonNode body) {
        List<String> names = new ArrayList<>();
        if (body == null || !body.isObject()) {
            return names;
        }
        body.fieldNames().forEachRemaining(name -> {
            if (!"data".equals(name) && !"sign".equals(name)) {
                names.add(name);
            }
        });
        names.sort(String::compareTo);
        return names;
    }

    static String md5Upper(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                hex.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }
}
