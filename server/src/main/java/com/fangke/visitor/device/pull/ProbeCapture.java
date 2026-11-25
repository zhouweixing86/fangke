package com.fangke.visitor.device.pull;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 车闸/相机协议「抓包」缓冲（docs/19 §7）。
 *
 * <p>用途：拿不到厂商「标准协议」文档时，把相机的 HTTP 推送地址临时指向本服务，
 * 打开 {@code fangke.device.openapi-probe=true}，平台把收到的原始报文留在这里，
 * 管理端 {@code GET /api/admin/vehicle/probe} 直接看，按真实报文适配，不必等文档。
 *
 * <p>只在内存里保留最近 20 条，重启即清空；默认关闭，不影响人行终端的 404 语义。
 */
@Component
public class ProbeCapture {

    /** 最多保留多少条。 */
    private static final int MAX = 20;
    /** 单条报文最多存多少字符（防止大图片撑爆内存）。 */
    private static final int MAX_BODY = 8_000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Deque<Map<String, Object>> recent = new ConcurrentLinkedDeque<>();

    public void record(String method, String uri, String query, String contentType, String body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", TIME.format(LocalDateTime.now()));
        row.put("method", method);
        row.put("uri", uri);
        row.put("query", query == null ? "" : query);
        row.put("contentType", contentType == null ? "" : contentType);
        String text = body == null ? "" : body;
        row.put("body", text.length() <= MAX_BODY ? text : text.substring(0, MAX_BODY) + "...(截断)");
        recent.addFirst(row);
        while (recent.size() > MAX) {
            recent.pollLast();
        }
    }

    public List<Map<String, Object>> recent() {
        return new ArrayList<>(recent);
    }

    public void clear() {
        recent.clear();
    }
}
