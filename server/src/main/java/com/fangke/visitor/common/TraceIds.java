package com.fangke.visitor.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * 请求级 traceId，用于串联日志与响应体。
 */
public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    private static final String ATTR = TraceIds.class.getName();

    private TraceIds() {
    }

    public static String current() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        Object existing = request.getAttribute(ATTR);
        if (existing instanceof String s) {
            return s;
        }
        String fromHeader = request.getHeader(HEADER);
        String traceId = (fromHeader == null || fromHeader.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : fromHeader;
        request.setAttribute(ATTR, traceId);
        return traceId;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
