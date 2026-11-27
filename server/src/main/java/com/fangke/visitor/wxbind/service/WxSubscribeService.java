package com.fangke.visitor.wxbind.service;

import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.wxbind.repository.WxBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信订阅消息（一次性订阅，docs/26 B1）。
 *
 * <p>为什么放在这里：站内消息只有打开小程序才看得到，审批人在微信里收到一条服务通知
 * 才知道有新的待办，访客也能第一时间知道审批结果。
 *
 * <p>约束：
 * <ul>
 *   <li>模板 ID 未配置、未配置 AppID/Secret、用户没授权（43101）时都**静默跳过**，
 *       绝不影响审批主流程；</li>
 *   <li>模板字段名用配置映射（{@code fangke.wx.subscribe.todo-fields}），换模板不改代码；</li>
 *   <li>字段值按微信长度限制截断（thing 20 / name 10 / phrase 5 / character_string 32）。</li>
 * </ul>
 */
@Service
public class WxSubscribeService {

    private static final Logger log = LoggerFactory.getLogger(WxSubscribeService.class);
    private static final String SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=";

    private final AppProperties props;
    private final WxacodeService wxacodeService;
    private final WxBindingRepository bindingRepository;
    private final VisitorRepository visitorRepository;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public WxSubscribeService(AppProperties props,
                              WxacodeService wxacodeService,
                              WxBindingRepository bindingRepository,
                              VisitorRepository visitorRepository) {
        this.props = props;
        this.wxacodeService = wxacodeService;
        this.bindingRepository = bindingRepository;
        this.visitorRepository = visitorRepository;
    }

    /** 是否可用：开关打开 + AppID/Secret 配置 + 至少配了一个模板 ID。 */
    public boolean configured() {
        AppProperties.Subscribe sub = props.getWx().getSubscribe();
        return sub.isEnabled() && wxacodeService.configured()
                && (!sub.getTodoTemplateId().isBlank() || !sub.getResultTemplateId().isBlank());
    }

    /** 审批待办：发给审批人（员工绑定的微信）。 */
    public void sendTodo(Long employeeId, String visitorName, String appointmentNo,
                         String timeRange, String purpose) {
        AppProperties.Subscribe sub = props.getWx().getSubscribe();
        if (!configured() || sub.getTodoTemplateId().isBlank() || employeeId == null) {
            return;
        }
        bindingRepository.findOpenidByEmployeeId(employeeId).ifPresent(openid -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("name", visitorName == null ? "访客" : visitorName);
            values.put("no", appointmentNo);
            values.put("time", timeRange);
            values.put("purpose", purpose);
            send("待办", sub.getTodoTemplateId(), sub.getTodoFields(), openid, values);
        });
    }

    /** 审批结果：发给访客（预约时绑定的微信）。 */
    public void sendResult(Long visitorId, String visitorName, String appointmentNo,
                           String timeRange, boolean passed, String reason) {
        AppProperties.Subscribe sub = props.getWx().getSubscribe();
        if (!configured() || sub.getResultTemplateId().isBlank() || visitorId == null) {
            return;
        }
        visitorRepository.findWxOpenidById(visitorId).ifPresent(openid -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("name", visitorName == null ? "访客" : visitorName);
            values.put("no", appointmentNo);
            values.put("time", timeRange);
            values.put("result", passed ? "已通过" : "已驳回");
            values.put("reason", reason);
            send("结果", sub.getResultTemplateId(), sub.getResultFields(), openid, values);
        });
    }

    private void send(String kind, String templateId, Map<String, String> fieldMap,
                      String openid, Map<String, String> values) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            fieldMap.forEach((logical, key) -> {
                String value = values.get(logical);
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    data.put(key, Map.of("value", truncate(key, value)));
                }
            });
            if (data.isEmpty()) {
                log.warn("订阅消息({})字段全空，跳过模板 {} 字段映射 {}", kind, templateId, fieldMap);
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("touser", openid);
            body.put("template_id", templateId);
            body.put("page", props.getWx().getSubscribe().getPage());
            body.put("data", data);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(SEND_URL + wxacodeService.accessToken()))
                            .timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var node = mapper.readTree(resp.body() == null ? "{}" : resp.body());
            int errcode = node.path("errcode").asInt(0);
            if (errcode != 0) {
                // 43101：用户未授权/已用完一次性订阅；47003：模板参数不合法 —— 都不该影响业务
                log.warn("订阅消息({})发送未成功 errcode={} errmsg={} openid={} template={}",
                        kind, errcode, node.path("errmsg").asText(""), mask(openid), templateId);
                return;
            }
            log.info("订阅消息({})已发送 openid={} template={}", kind, mask(openid), templateId);
        } catch (Exception e) {
            log.warn("订阅消息({})发送异常：{}", kind, e.getMessage());
        }
    }

    /** 按模板关键词前缀截断（微信对长度有限制，超长会 47003）。 */
    static String truncate(String key, String value) {
        int limit = 32;
        if (key.startsWith("thing")) {
            limit = 20;
        } else if (key.startsWith("name")) {
            limit = 10;
        } else if (key.startsWith("phrase")) {
            limit = 5;
        } else if (key.startsWith("character_string")) {
            limit = 32;
        } else if (key.startsWith("number") || key.startsWith("amount") || key.startsWith("digit")) {
            limit = 32;
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private String mask(String openid) {
        if (openid == null || openid.length() <= 8) {
            return "***";
        }
        return openid.substring(0, 4) + "***" + openid.substring(openid.length() - 4);
    }
}
