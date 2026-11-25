package com.fangke.visitor.device.pull;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 车闸/相机协议抓包端点（docs/19 §7，默认关闭）。
 *
 * <p>只有 {@code fangke.device.openapi-probe=true} 时才注册，兜住 {@code /openapi/**} 下
 * **没有具体处理器**的路径：把原始报文存进 {@link ProbeCapture} 并回 200，
 * 便于在拿不到厂商「标准协议」文档时，从真实报文反推字段。
 *
 * <p>为什么必须默认关闭：人行终端（YC-P208）依赖「未知路径返回 404」判定平台是否正常
 * （docs/14），兜底 200 会让它误判；抓包属于临时排障手段，用完要关掉。
 */
@RestController
@ConditionalOnProperty(name = "fangke.device.openapi-probe", havingValue = "true")
public class OpenApiProbeController {

    private static final Logger log = LoggerFactory.getLogger(OpenApiProbeController.class);

    private final ProbeCapture capture;

    public OpenApiProbeController(ProbeCapture capture) {
        this.capture = capture;
    }

    @RequestMapping("/openapi/**")
    public Map<String, Object> capture(HttpServletRequest request,
                                       @RequestBody(required = false) String body) {
        capture.record(request.getMethod(), request.getRequestURI(), request.getQueryString(),
                request.getContentType(), body);
        log.warn("抓包 /openapi 未知路径 {} {} body={}", request.getMethod(), request.getRequestURI(),
                body == null ? "" : (body.length() > 500 ? body.substring(0, 500) + "..." : body));
        return Map.of("code", 0, "msg", "ok");
    }
}
