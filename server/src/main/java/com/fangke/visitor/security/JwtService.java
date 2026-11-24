package com.fangke.visitor.security;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 HS256 JWT 实现。
 *
 * <p>只做本项目需要的最小能力（签发 / 校验 / 取 claim），不引入第三方 JWT 库；
 * 若后续需要 RS256 或标准 claim 校验，可整体替换本类而不影响调用方。
 */
@Component
public class JwtService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(AppProperties properties) {
        this.secret = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = properties.getJwt().getTtl().toSeconds();
    }

    public String issue(long userId, String username, List<String> roles) {
        return issue(userId, username, roles, null);
    }

    /**
     * @param bindId 微信角色绑定 ID（V29），小程序审批接口据此取已授予角色；后台账号传 {@code null}
     */
    public String issue(long userId, String username, List<String> roles, Long bindId) {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode(String.format(
                "{\"uid\":%d,\"sub\":\"%s\",\"roles\":[%s],\"bindId\":%d,\"exp\":%d}",
                userId,
                escape(username),
                roles.stream().map(r -> "\"" + escape(r) + "\"").reduce((a, b) -> a + "," + b).orElse(""),
                bindId == null ? 0L : bindId,
                Instant.now().getEpochSecond() + ttlSeconds));
        String signingInput = header + "." + payload;
        return signingInput + "." + encode(sign(signingInput));
    }

    /** token 有效期（秒），供前端展示/自动续期策略使用。 */
    public long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 校验并返回 payload 中的 uid；失败抛 2001。
     */
    public long verifyAndGetUserId(String token) {
        Map<String, Object> claims = parse(token);
        Object exp = claims.get("exp");
        long expSeconds = exp instanceof Number n ? n.longValue() : 0L;
        if (expSeconds <= Instant.now().getEpochSecond()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token 已过期");
        }
        Object uid = claims.get("uid");
        if (!(uid instanceof Number n)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return n.longValue();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token 格式错误");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = sign(signingInput);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token 签名错误");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token 签名错误");
        }
        String json = new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        return new JsonMapParser(json).parse();
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    private String encode(String raw) {
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(byte[] raw) {
        return ENCODER.encodeToString(raw);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 极简 JSON 平铺解析（只处理本类签发的结构：字符串/数字/字符串数组）。
     */
    private static final class JsonMapParser {

        private final String json;
        private int pos;

        JsonMapParser(String json) {
            this.json = json;
        }

        Map<String, Object> parse() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            while (peek() != '}') {
                String key = readString();
                skipWs();
                expect(':');
                skipWs();
                Object value = readValue();
                map.put(key, value);
                skipWs();
                if (peek() == ',') {
                    pos++;
                    skipWs();
                }
            }
            return map;
        }

        private Object readValue() {
            char c = peek();
            if (c == '"') {
                return readString();
            }
            if (c == '[') {
                pos++;
                List<String> list = new java.util.ArrayList<>();
                skipWs();
                while (peek() != ']') {
                    list.add(readString());
                    skipWs();
                    if (peek() == ',') {
                        pos++;
                        skipWs();
                    }
                }
                pos++;
                return list;
            }
            int start = pos;
            while (pos < json.length() && "-0123456789".indexOf(json.charAt(pos)) >= 0) {
                pos++;
            }
            return Long.parseLong(json.substring(start, pos));
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = json.charAt(pos++);
                if (c == '\\') {
                    char next = json.charAt(pos++);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        default -> sb.append(next);
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private void skipWs() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            skipWs();
            return json.charAt(pos);
        }

        private void expect(char c) {
            skipWs();
            if (json.charAt(pos) != c) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "token 内容错误");
            }
            pos++;
        }
    }
}
