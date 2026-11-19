package com.fangke.visitor.device.callback;

import com.fangke.visitor.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 设备回调来源校验（docs/11 §0.4）。
 *
 * <p>厂商设备不发自定义请求头，所以只能用「来源 IP 白名单 + SN 校验 + password」三层。
 * 本类负责第一层：配置为空时不限制（仅限内网部署场景）。
 */
@Component
public class DeviceCallbackGuard {

    private static final Logger log = LoggerFactory.getLogger(DeviceCallbackGuard.class);

    private final AppProperties props;

    public DeviceCallbackGuard(AppProperties props) {
        this.props = props;
    }

    public boolean allowed(HttpServletRequest request) {
        var cidrs = props.getDevice().getCallbackAllowedCidrs();
        if (cidrs == null || cidrs.isEmpty()) {
            return true;
        }
        String clientIp = clientIp(request);
        boolean ok = cidrs.stream().anyMatch(cidr -> matches(clientIp, cidr));
        if (!ok) {
            log.warn("设备回调来源不在白名单 ip={} allowed={}", clientIp, cidrs);
        }
        return ok;
    }

    public String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 仅支持 IPv4 的 a.b.c.d/n 或精确 IP。 */
    static boolean matches(String ip, String cidr) {
        if (ip == null || cidr == null || cidr.isBlank()) {
            return false;
        }
        String range = cidr.trim();
        if (!range.contains("/")) {
            return range.equals(ip);
        }
        String[] parts = range.split("/");
        if (parts.length != 2) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        byte[] network = toBytes(parts[0]);
        byte[] client = toBytes(ip);
        if (network == null || client == null || prefix < 0 || prefix > 32) {
            return false;
        }
        int fullBytes = prefix / 8;
        int remainder = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != client[i]) {
                return false;
            }
        }
        if (remainder == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainder) & 0xFF;
        return (network[fullBytes] & mask) == (client[fullBytes] & mask);
    }

    private static byte[] toBytes(String ip) {
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
