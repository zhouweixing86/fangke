package com.fangke.visitor.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 根路径连通性探针。
 *
 * <p>现场排查设备接入时，第一个要回答的问题是「设备能不能访问到平台」，
 * 但根路径命不中任何映射时会被兜底异常处理成 500，看起来像平台挂了。
 * 这里给 {@code /} 与 {@code /health} 一个稳定的 200，手机/浏览器/设备都能直接验证。
 */
@RestController
public class PlatformRootController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping({"/", "/health"})
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("msg", "fangke visitor platform is up");
        body.put("time", LocalDateTime.now().format(TIME));
        return body;
    }

    /** 浏览器自动请求图标，直接给 204，避免落进异常处理刷日志。 */
    @RequestMapping("/favicon.ico")
    public org.springframework.http.ResponseEntity<Void> favicon() {
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
