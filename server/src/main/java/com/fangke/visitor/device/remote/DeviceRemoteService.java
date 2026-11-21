package com.fangke.visitor.device.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备远程管理：平台<b>主动</b>连设备的 9090 端口调下行接口。
 *
 * <p>这套接口来自厂商文档 item 231「下行接口 1~24」（设备作为服务端），和平台主链路
 * （设备轮询 {@code /openapi/v1/**}）是<b>两个方向</b>：
 * <ul>
 *   <li>主链路：设备来问平台 → 名单、开门、记录（不需要设备有固定 IP 被平台访问）</li>
 *   <li>本类：平台去问设备 → 读参数、校时、抓拍、弹框、重启等现场运维动作（要求局域网可达）</li>
 * </ul>
 *
 * <p>因此这些能力只在「设备与平台同网」时有意义；云端部署时调不通，界面上会明确提示走「本地管理」。
 * 多数接口用设备管理员密码（{@code pass}）代替 token，个别版本要 {@code deviceLogin} 返回的 token，
 * 这里默认走密码，失败信息原样抛给前端便于现场判断。
 */
@Service
public class DeviceRemoteService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRemoteService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties props;
    private final HttpClient http;

    public DeviceRemoteService(AppProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(2, props.getDevice().getRemoteTimeoutSeconds())))
                .build();
    }

    /** 设备 Web 后台地址（本地管理用）：直接让现场人员用浏览器登录设备自己的页面。 */
    public String localAdminUrl(Device device) {
        return "http://" + device.getIp() + ":" + props.getDevice().getRemotePort() + "/home.html";
    }

    /* ---------------- 只读 ---------------- */

    /** 版本/运行状态：型号、固件、本地人数、运行时长、WiFi、IP。 */
    public Map<String, Object> version(Device device) {
        JsonNode body = call(device, "/getDeviceVersion", Map.of());
        Map<String, Object> view = new LinkedHashMap<>();
        view.putAll(looseObject(body.path("data")));
        view.put("raw", body);
        return view;
    }

    /** 设备全部参数（音量、识别模式、时间组、各类开关…）。 */
    public Map<String, Object> parameters(Device device) {
        JsonNode body = call(device, "/getDeviceParameter", Map.of());
        return looseObject(body.path("data"));
    }

    /** 设备时间 + 平台时间，用于判断时钟偏移。 */
    public Map<String, Object> time(Device device) {
        JsonNode body = call(device, "/getDeviceTime", Map.of());
        Map<String, Object> view = new LinkedHashMap<>();
        Map<String, Object> data = looseObject(body.path("data"));
        Object deviceTime = data.get("time");
        if (deviceTime == null) {
            deviceTime = data.get("data"); // 个别固件直接把时间字符串放在 data
        }
        view.put("deviceTime", deviceTime);        view.put("platformTime", LocalDateTime.now().format(DATETIME));
        view.put("raw", body);
        return view;
    }

    /* ---------------- 写操作 ---------------- */

    /** 立即开闸（不经心跳，秒级生效；要求平台能直连设备）。 */
    public Map<String, Object> openGate(Device device) {
        return toMap(call(device, "/setDeviceRemoteOpen", Map.of()));
    }

    /** 校时：把设备时间设成平台当前时间。 */
    public Map<String, Object> syncTime(Device device) {
        Map<String, Object> data = Map.of("time", LocalDateTime.now().format(DATETIME));
        return toMap(call(device, "/setDeviceTime", data));
    }

    /**
     * 屏幕弹框提示（现场调试/引导用）。
     *
     * @param msg    提示文字，建议 ≤16 字，可用 \n 换行
     * @param color  0 绿色 / 1 红色
     * @param playId 语音编号，0 或不在语音表内则不播报
     */
    public Map<String, Object> showTip(Device device, String msg, Integer color, Integer playId) {
        if (msg == null || msg.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "弹框内容不能为空");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tipMsg", msg.trim());
        data.put("color", color == null ? 0 : color);
        data.put("playId", playId == null ? 0 : playId);
        return toMap(call(device, "/setShowTipMsg", data));
    }

    /** 抓拍一张现场图片：设备把 base64 塞在 data 的 snapPicBase64 字段里，这里解出来给前端直接用。 */
    public Map<String, Object> snapshot(Device device) {
        JsonNode body = call(device, "/getDeviceSnapPicture", Map.of());
        Map<String, Object> view = new LinkedHashMap<>();
        Map<String, Object> data = looseObject(body.path("data"));
        Object image = data.get("snapPicBase64");
        if (image == null) {
            image = data.get("data");
        }
        view.put("image", image);
        view.put("raw", body);
        return view;
    }

    /** 闸机常开：{@code enabled=false} 表示关闭常开。 */
    public Map<String, Object> gateHold(Device device, boolean enabled, Integer minutes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openFlag", enabled ? 1 : 0);
        data.put("openTime", minutes == null ? 1 : Math.max(1, minutes));
        return toMap(call(device, "/setDeviceOpenTime", data));
    }

    /** 重启设备（会自动断开，返回成功即可）。 */
    public Map<String, Object> reboot(Device device) {
        return toMap(call(device, "/setDeviceReboot", Map.of()));
    }

    /** 恢复出厂参数（危险操作，调用前必须二次确认）。 */
    public Map<String, Object> restoreDefaults(Device device) {
        return toMap(call(device, "/restoreDeviceDefaultParameter", Map.of()));
    }

    /**
     * 允许从平台改的设备参数<b>白名单</b>。
     *
     * <p>设备参数有 100+ 项，全量开放编辑迟早会被改到失联（IP、平台地址、识别阈值…）。
     * 这里只放行"改了不影响设备接入、且平台业务必须用"的几个开关。
     */
    private static final Map<String, String> SAFE_SWITCHES = Map.of(
            "videoScanQRcode", "摄像头扫码（访客二维码进闸需要）",
            "QROpenEnable", "二维码开门总开关",
            "PasswordOpenEnable", "密码开门");

    /** 可远程切换的开关清单（给前端渲染用）。 */
    public Map<String, String> safeSwitches() {
        return SAFE_SWITCHES;
    }

    /**
     * 批量切换安全开关。
     *
     * @param switches 形如 {@code {videoScanQRcode: true}}；只接受白名单内的键，其余直接拒绝
     */
    public Map<String, Object> setSwitches(Device device, Map<String, Object> switches) {
        if (switches == null || switches.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "没有要修改的开关");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : switches.entrySet()) {
            String key = entry.getKey();
            if (!SAFE_SWITCHES.containsKey(key)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "不允许远程修改参数 " + key + "（请在设备本地后台操作）");
            }
            data.put(key, toDeviceFlag(entry.getValue()));
        }
        return toMap(call(device, "/setDeviceParameter", data));
    }

    /** 设备用 0/1 表示开关。 */
    private int toDeviceFlag(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Number n) {
            return n.intValue() == 0 ? 0 : 1;
        }
        String text = String.valueOf(value).trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text) ? 1 : 0;
    }

    /**
     * 从设备本地名单里删掉一条（运维手段）。
     *
     * <p>正常增删都走授权与指令队列；但库里数据被清理、设备换人、指令丢了时会留下孤儿名单，
     * 现场需要能直接清掉。这里只做"删单条"，不提供"改名/改有效期"，避免绕过业务。
     *
     * @param usertype white / black / visitor
     */
    public Map<String, Object> deleteLocalPerson(Device device, String idno, String usertype) {
        if (idno == null || idno.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "证件号不能为空");
        }
        String type = (usertype == null || usertype.isBlank()) ? "white" : usertype.trim().toLowerCase();
        if (!java.util.Set.of("white", "black", "visitor").contains(type)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "usertype 只能是 white / black / visitor");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("idno", idno.trim().toUpperCase());
        data.put("usertype", type);
        return toMap(call(device, "/deleteDeviceWhiteList", data));
    }

    /* ---------------- 内部 ---------------- */

    private JsonNode call(Device device, String path, Map<String, Object> data) {
        if (device.getIp() == null || device.getIp().isBlank()) {
            throw new BusinessException(ErrorCode.DEVICE_PARAM_ERROR, "设备没有登记 IP，无法远程访问");
        }
        int port = props.getDevice().getRemotePort();
        String url = "http://" + device.getIp() + ":" + port + path;
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("pass", props.getDevice().getRemotePassword());
        if (!data.isEmpty()) {
            payload.set("data", MAPPER.valueToTree(data));
        }
        int timeout = Math.max(2, props.getDevice().getRemoteTimeoutSeconds());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = parseJson(response.body());
            if (response.statusCode() != 200) {
                throw new BusinessException(ErrorCode.DEVICE_OFFLINE,
                        "设备返回 HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
            }
            int result = body.path("result").asInt(0);
            if (result != 0) {
                String message = body.path("message").asText("设备返回失败");
                // result=2 是密码错误，单独提示，现场最常见
                ErrorCode code = result == 2 ? ErrorCode.DEVICE_PASSWORD : ErrorCode.DEVICE_PARAM_ERROR;
                throw new BusinessException(code, "设备拒绝：" + message);
            }
            log.info("设备远程调用 device={} path={} 成功", device.getDeviceSn(), path);
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BusinessException(ErrorCode.DEVICE_OFFLINE,
                    "设备无响应（" + timeout + " 秒超时）：确认设备在线且与平台同网段");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DEVICE_OFFLINE,
                    "无法连接设备 " + url + "：" + e.getMessage()
                            + "。设备离线、端口未开放，或平台与设备不在同一网络时属正常现象");
        }
    }

    private JsonNode parseJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DEVICE_PARAM_ERROR, "设备返回的不是合法 JSON：" + abbreviate(text));
        }
    }

    /** 有些固件把 data 塞成「JSON 字符串」，这里宽松解析。 */
    private Map<String, Object> parseLoose(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            return node.isObject() ? toMap(node) : Map.of("data", text);
        } catch (Exception e) {
            return Map.of("data", text);
        }
    }

    /** data 可能是对象、也可能是一段 JSON 字符串，统一成 Map。 */
    private Map<String, Object> looseObject(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return Map.of();
        }
        if (data.isObject()) {
            return toMap(data);
        }
        if (data.isTextual()) {
            return parseLoose(data.asText());
        }
        return Map.of("data", data.asText());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return MAPPER.convertValue(node, Map.class);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
