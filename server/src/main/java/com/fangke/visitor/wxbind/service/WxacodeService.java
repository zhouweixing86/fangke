package com.fangke.visitor.wxbind.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * 微信小程序码（wxacode.getUnlimited，C-81）。
 *
 * <p>为什么需要它：链接二维码用普通微信「扫一扫」只会用浏览器打开网页，进不了小程序；
 * 小程序码是图片，扫码后直接唤起小程序并带上 scene 参数。
 *
 * <p>必须在配置了 {@code WX_APP_ID / WX_SECRET} 且小程序已发布对应版本后可用，
 * 否则抛 {@link ErrorCode#THIRD_PARTY_ERROR}，前端回退成链接二维码。
 */
@Service
public class WxacodeService {

    private static final Logger log = LoggerFactory.getLogger(WxacodeService.class);
    private static final String TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s";
    private static final String CODE_URL =
            "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=";

    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private volatile String accessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public WxacodeService(AppProperties props) {
        this.props = props;
    }

    /** AppID/Secret 是否已配置（未配置时前端不要请求小程序码）。 */
    public boolean configured() {
        AppProperties.Wx wx = props.getWx();
        return wx.getAppId() != null && !wx.getAppId().isBlank()
                && wx.getSecret() != null && !wx.getSecret().isBlank();
    }

    /**
     * 生成小程序码 PNG（base64 返回，便于前端直接当 data URL 用）。
     *
     * @param page  小程序页面，如 {@code pages/bind/bind}
     * @param scene 场景值，≤32 字符，如 {@code t=<token>}
     */
    public String unlimitedBase64(String page, String scene) {
        if (!configured()) {
            throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR,
                    "未配置小程序 AppID/Secret（WX_APP_ID / WX_SECRET），无法生成小程序码");
        }
        if (scene == null || scene.isBlank() || scene.length() > 32) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "scene 长度必须在 1~32 之间");
        }
        try {
            Map<String, Object> body = Map.of(
                    "scene", scene,
                    "page", page,
                    "check_path", false,
                    "env_version", envVersion(),
                    "width", 430);
            HttpResponse<byte[]> resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(CODE_URL + accessToken()))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) {
                throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR, "微信返回空图片");
            }
            // 失败时微信返回 JSON（{"errcode":...}），成功时是 PNG 二进制
            if (bytes[0] == '{') {
                String err = new String(bytes, StandardCharsets.UTF_8);
                log.warn("生成小程序码失败 page={} scene={} resp={}", page, scene, err);
                throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR, "生成小程序码失败：" + err);
            }
            return Base64.getEncoder().encodeToString(bytes);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR, "生成小程序码异常：" + e.getMessage());
        }
    }

    private String envVersion() {
        String v = props.getWx().getEnvVersion();
        return v == null || v.isBlank() ? "release" : v;
    }

    /** 供同包内的订阅消息复用（同一 AppID 的 access_token 两小时有效，内部有缓存）。 */
    synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return accessToken;
        }
        AppProperties.Wx wx = props.getWx();
        String url = String.format(TOKEN_URL, wx.getAppId(), wx.getSecret());
        String body = http.send(HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        var node = mapper.readTree(body);
        String token = node.path("access_token").asText("");
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.THIRD_PARTY_ERROR,
                    "获取小程序 access_token 失败：" + node.path("errmsg").asText(body));
        }
        long expiresIn = node.path("expires_in").asLong(7200);
        accessToken = token;
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 300));
        log.info("已获取小程序 access_token，{}s 后过期", expiresIn);
        return token;
    }
}
