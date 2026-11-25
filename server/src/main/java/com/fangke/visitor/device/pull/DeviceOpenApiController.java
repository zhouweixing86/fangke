package com.fangke.visitor.device.pull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.service.DeviceParamOverrides;
import com.fangke.visitor.qr.service.QrTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新终端 HTTP 协议入口（协议文档《HTTP 通讯协议，客户端版》）。
 *
 * <p><b>返回体必须是厂商格式</b>：{@code {"code":0,"msg":"...","data":...}}，
 * 不能套平台的 {@code ApiResponse}，否则设备会当成失败并反复重试。
 *
 * <p>设备是"客户端"，所有请求都由它发起；平台只能通过心跳响应里的 {@code action} 指挥它。
 * 字段含义与实现映射见 docs/14。
 */
@RestController
@RequestMapping("/openapi/v1")
public class DeviceOpenApiController {

    private static final Logger log = LoggerFactory.getLogger(DeviceOpenApiController.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 密码开门的 AES 参数（协议 §3.33 固定示例值，设备侧可改，改这里即可）。 */
    private static final String PWD_AES_KEY = "infoexchange4api";
    private static final String PWD_AES_IV = "activevector4api";

    private final OpenApiDeviceService service;
    private final AppProperties props;

    public DeviceOpenApiController(OpenApiDeviceService service, AppProperties props) {
        this.service = service;
        this.props = props;
    }

    /* ---------------- 心跳 ---------------- */

    @PostMapping({"/heartBeat", "/heartbeat"})
    public Map<String, Object> heartbeat(@RequestBody(required = false) String rawText,
                                        HttpServletRequest request) {
        JsonNode body = parse(rawText);
        Map<String, Object> result = new LinkedHashMap<>();
        String deviceNo = text(body, "deviceno");
        if (!verify(body, rawText, "heartBeat")) {
            return fail("签名错误");
        }
        Device device = service.resolveOrRegister(deviceNo, clientIp(request));
        if (device == null) {
            return fail("缺少 deviceno");
        }
        service.touchHeartbeat(device, clientIp(request));
        service.bindMac(device, text(body, "mac"));
        result.put("code", 0);
        result.put("msg", "成功");
        result.put("data", service.heartbeat(device));
        return result;
    }

    /* ---------------- 名单 ---------------- */

    /** 平台全量名单索引（设备开机/重建时拉取）。 */
    @PostMapping("/getAllDeviceWhiteList")
    public Map<String, Object> allDeviceWhiteList(@RequestBody(required = false) String rawText) {
        JsonNode body = parse(rawText);
        if (!verify(body, rawText, "getAllDeviceWhiteList")) {
            return fail("签名错误");
        }
        Device device = service.resolveOrRegister(text(body, "deviceno"), null);
        if (device == null) {
            return fail("缺少 deviceno");
        }
        Map<String, Object> result = ok("获取名单列表成功");
        result.put("data", service.personIndex(device));
        return result;
    }

    /** 设备来取"要新增的人"。 */
    @PostMapping("/addDevicWhiteInfo")
    public Map<String, Object> addDeviceWhiteInfo(@RequestBody(required = false) String rawText) {
        return claimChanges(rawText, PullCommandRepository.TYPE_ADD_PERSON, "新增名单");
    }

    /** 设备来取"要更新的人"。 */
    @PostMapping("/updateDeviceWhiteInfo")
    public Map<String, Object> updateDeviceWhiteInfo(@RequestBody(required = false) String rawText) {
        return claimChanges(rawText, PullCommandRepository.TYPE_UPDATE_PERSON, "更新名单");
    }

    /** 设备来取"要删除的人"。 */
    @PostMapping("/delDevicWhiteList")
    public Map<String, Object> delDeviceWhiteList(@RequestBody(required = false) String rawText) {
        return claimChanges(rawText, PullCommandRepository.TYPE_DEL_PERSON, "删除名单");
    }

    /** 单个人员详情（含照片 base64）。设备对 getDevicWhiteInfo / getDeviceWhiteInfo 两种拼写都兼容。 */
    @PostMapping({"/getDevicWhiteInfo", "/getDeviceWhiteInfo"})
    public Map<String, Object> deviceWhiteInfo(@RequestBody(required = false) String rawText) {
        JsonNode body = parse(rawText);
        if (!verify(body, rawText, "getDevicWhiteInfo")) {
            return fail("签名错误");
        }
        Device device = service.resolveOrRegister(text(body, "deviceno"), null);
        if (device == null) {
            return fail("缺少 deviceno");
        }
        JsonNode data = body == null ? null : body.get("data");
        String idno = data == null ? null : text(data, "idno");
        if (idno == null || idno.isBlank()) {
            return fail("缺少 idno");
        }
        Map<String, Object> detail = service.personDetail(device, idno);
        if (detail == null) {
            // 按协议返回空对象，设备侧会跳过该人
            return fail("名单不存在或已失效：" + idno);
        }
        Map<String, Object> result = ok("请求详细信息");
        result.put("data", detail);
        return result;
    }

    /** 名单增删改结果回执。 */
    @PostMapping("/syncWhiteResult")
    public Map<String, Object> syncWhiteResult(@RequestBody(required = false) String rawText) {
        JsonNode body = parse(rawText);
        if (!verify(body, rawText, "syncWhiteResult")) {
            return fail("签名错误");
        }
        Device device = service.resolveOrRegister(text(body, "deviceno"), null);
        if (device == null) {
            return fail("缺少 deviceno");
        }
        JsonNode data = body == null ? null : body.get("data");
        if (data == null) {
            return fail("缺少 data");
        }
        service.syncWhiteResult(device, text(data, "idno"), intValue(data, "resulttype"),
                intValue(data, "resultcode"), text(data, "msg"));
        return ok("成功");
    }

    /** 名单总数（设备上报/请求，按协议返回平台侧统计，保证字段不空）。 */
    @PostMapping("/getDeviceWhiteListCount")
    public Map<String, Object> whiteListCount(@RequestBody(required = false) String rawText) {
        JsonNode body = parse(rawText);
        if (!verify(body, rawText, "getDeviceWhiteListCount")) {
            return fail("签名错误");
        }
        Device device = service.resolveOrRegister(text(body, "deviceno"), null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("whitelistcount", device == null ? 0 : service.personIndex(device).size());
        Map<String, Object> result = ok("成功");
        result.put("data", data);
        return result;
    }

    /* ---------------- 参数 / 时间 ---------------- */

    @PostMapping("/syncDeviceTime")
    public Map<String, Object> syncDeviceTime(@RequestBody(required = false) String rawText) {
        JsonNode body = parse(rawText);
        if (!verify(body, rawText, "syncDeviceTime")) {
            return fail("签名错误");
        }
        // 设备按心跳 action=syncDeviceTime 回来取时间；收到就算这次校时指令完成，
        // 否则队列里的 SYNC_TIME 会一直是「执行中」，超时后又重排（管理端看起来永远没做完）。
        Device device = service.resolveOrRegister(text(body, "deviceno"), null);
        if (device != null) {
            service.completeSimpleCommand(device.getId(), PullCommandRepository.TYPE_SYNC_TIME, "设备已取走时间");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", TIME.format(LocalDateTime.now()));
        Map<String, Object> result = ok("成功");
        result.put("data", data);
        return result;
    }

    /**
     * 设备请求参数。
     *
     * <p>本协议里没有"平台读设备参数"的接口，所以这里返回的是<b>平台期望值</b>
     * （与 docs/14 §5 的字段表一致），由设备套用。只放必要字段，其余走设备默认值。
     */
    @PostMapping("/syncDeviceParameter")
    public Map<String, Object> syncDeviceParameter(@RequestBody(required = false) String rawText) {
        JsonNode body = parse(rawText);
        if (!verify(body, rawText, "syncDeviceParameter")) {
            return fail("签名错误");
        }
        Device device = service.resolveOrRegister(text(body, "deviceno"), null);
        if (device != null) {
            service.completeSimpleCommand(device.getId(), PullCommandRepository.TYPE_PARAM_SYNC, "设备已拉取参数");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        // 双向设备（direction=2）不下发 inout：协议只有 0 出口 / 1 入口，双向由设备侧的模式决定；
        // 通行方向以设备上报的记录 inout 为准（平台不推断）。
        if (device == null) {
            data.put("inout", 1);
        } else if (device.getDirection() != null && device.getDirection() != 2) {
            data.put("inout", device.getDirection());
        }
        data.put("name", device == null ? "门禁终端" : device.getDeviceName());
        data.put("recogModeDB", 1);
        data.put("recogModeIC", 1);
        data.put("recogModeID", 1);
        data.put("livenessEnable", 0);
        // 二维码交给平台判定：thirdOpenDoorLocal=0 + thirdQrUrl 指向本服务
        data.put("thirdOpenDoorLocal", 0);
        data.put("thirdQrUrl", qrPushUrl());
        // 远程开门/校时都靠心跳下发，心跳间隔别太大，否则操作会明显延迟
        data.put("thirdHttpUrl", null);
        // 平台期望的开关（device.param_overrides，白名单键）最后合并：管理端改了就一定盖掉默认值
        if (device != null) {
            DeviceParamOverrides.applyTo(device.getParamOverrides(), data);
        }
        Map<String, Object> result = ok("成功");
        result.put("data", data);
        return result;
    }

    /* ---------------- 记录推送 ---------------- */

    @PostMapping({"/uploadDetectRecord", "/uploadDetectRecord/"})
    public Map<String, Object> uploadDetectRecord(@RequestBody(required = false) String rawText,
                                                  HttpServletRequest request) {
        JsonNode body = parse(rawText);
        String deviceNo = text(body, "deviceno");
        Device device = service.resolveOrRegister(deviceNo, clientIp(request));
        if (device == null) {
            return fail("缺少 deviceno");
        }
        // 记录推送不校验签名：文档 4.1 的示例没有 sign，且丢记录的代价比假记录高
        JsonNode data = body == null ? null : body.get("data");
        if (data == null) {
            return fail("缺少 data");
        }
        service.receiveRecord(device, data, rawText);
        return ok("成功");
    }

    /* ---------------- 二维码 / 密码开门 ---------------- */

    /**
     * 二维码推送（设备把扫到的码交给我们判定）。
     *
     * <p>返回 {@code openGate=1} 才开闸；这里复用 M7 的 60 秒票据 + 授权判定，
     * 所以撤销授权/作废码能立即生效，不需要重新下发名单。
     */
    @PostMapping({"/qrCodePush", "/qr/push"})
    public Map<String, Object> qrCodePush(@RequestBody(required = false) String rawText,
                                          HttpServletRequest request) {
        JsonNode body = parse(rawText);
        String deviceNo = firstNonBlank(text(body, "deviceno"), text(body, "sn"), text(body, "deviceSn"));
        Device device = service.resolveOrRegister(deviceNo, clientIp(request));
        String code = extractQrCode(rawText, body, request);
        log.info("二维码推送 device={} code={} from={} raw={}", deviceNo, code, clientIp(request),
                rawText == null ? "null" : rawText.substring(0, Math.min(300, rawText.length())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionName", "qrCodePush");
        if (device == null || code == null || code.isBlank()) {
            result.put("resultcode", "0");
            result.put("message", "缺少设备号或二维码内容");
            result.put("openGate", 0);
            return result;
        }
        try {
            QrTicketService.VerifyResult verify = service.verifyQr(device, code.trim());
            result.put("resultcode", verify.allowed() ? "1" : "0");
            result.put("message", verify.reason());
            result.put("msg", verify.reason());
            result.put("openGate", verify.allowed() ? 1 : 0);
            if (verify.allowed()) {
                result.put("idno", verify.idCardMask());
            }
        } catch (Exception e) {
            log.error("二维码判定异常 device={}", device.getDeviceSn(), e);
            result.put("resultcode", "0");
            result.put("message", "系统异常，请稍候");
            result.put("openGate", 0);
        }
        return result;
    }

    /**
     * 密码开门（协议 §3.33）。
     *
     * <p>访客系统里没有"人员密码"概念，默认关闭；需要时可以在这里接员工密码/临时密码。
     * 数据结构按协议返回，避免设备报错。
     */
    @PostMapping({"/checkPwd", "/pwd/check"})
    public Map<String, Object> checkPwd(@RequestBody(required = false) String rawText,
                                        HttpServletRequest request) {
        JsonNode body = parse(rawText);
        String deviceNo = text(body, "deviceno");
        String cipher = text(body, "data");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actioname", "checkpwd");
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
        String plain = decryptPwd(cipher);
        log.info("密码开门请求 device={} 解密结果={}", deviceNo, plain == null ? "失败" : "成功");
        result.put("resultcode", "0");
        result.put("message", "未启用密码开门，请使用证件或二维码");
        return result;
    }

    /* ---------------- 内部 ---------------- */

    private Map<String, Object> claimChanges(String rawText, String cmdType, String msg) {
        JsonNode body = parse(rawText);
        if (!verify(body, rawText, cmdType)) {
            return fail("签名错误");
        }
        Device device = service.resolveOrRegister(text(body, "deviceno"), null);
        if (device == null) {
            return fail("缺少 deviceno");
        }
        Map<String, Object> result = ok(msg);
        result.put("data", service.claimPersonChanges(device, cmdType));
        return result;
    }

    private Map<String, Object> ok(String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("msg", msg);
        return result;
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 1);
        result.put("msg", msg);
        result.put("data", List.of());
        return result;
    }

    private boolean verify(JsonNode body, String rawText, String action) {
        if (!props.getDevice().isPullVerifySign()) {
            log.debug("签名校验已关闭，跳过 action={} body={}", action, rawText);
            return true;
        }
        if (body == null) {
            return false;
        }
        String key = props.getDevice().getPullSignKey();
        boolean valid = OpenApiSign.verify(body, key);
        if (!valid) {
            log.warn("设备签名校验失败 action={} 期望={} 实际={} 参与签名字段={} body={}",
                    action, OpenApiSign.expectedSign(body, key), text(body, "sign"),
                    OpenApiSign.signedFields(body), rawText);
        }
        return valid;
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            log.warn("设备报文不是合法 JSON：{}", raw);
            return null;
        }
    }


    /** 真实客户端 IP：云端经 nginx 反代，getRemoteAddr() 会是 127.0.0.1，优先取 X-Forwarded-For / X-Real-IP。 */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 各家固件送码的写法不一样，能认的都认一遍：
     * {@code data.qrCode}、{@code data} 里再套一层 JSON 或纯文本、顶层 {@code qrCode}、
     * 整个请求体就是码、以及 {@code ?qrCode=} 查询参数。
     */
    private String extractQrCode(String rawText, JsonNode body, HttpServletRequest request) {
        String code = null;
        JsonNode data = body == null ? null : body.get("data");
        if (data != null) {
            if (data.isTextual()) {
                JsonNode inner = parse(data.asText());
                code = inner == null ? data.asText() : text(inner, "qrCode");
            } else {
                code = text(data, "qrCode");
            }
        }
        if (code == null || code.isBlank()) {
            code = firstNonBlank(text(body, "qrCode"), text(body, "code"), text(body, "qrContent"));
        }
        if (code == null || code.isBlank()) {
            code = firstNonBlank(request.getParameter("qrCode"), request.getParameter("code"));
        }
        if ((code == null || code.isBlank()) && body == null && rawText != null && !rawText.isBlank()) {
            code = rawText.trim();
        }
        return code;
    }

    private Integer intValue(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String decryptPwd(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isBlank()) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(PWD_AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(PWD_AES_IV.getBytes(StandardCharsets.UTF_8)));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(cipherBase64.trim()));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("密码开门解密失败：{}", e.getMessage());
            return null;
        }
    }

    /** 二维码推送地址：设备侧 thirdQrUrl 指向这里。 */
    private String qrPushUrl() {
        String base = props.getDevice().getPullPublicBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        return base.endsWith("/") ? base + "openapi/v1/qrCodePush" : base + "/openapi/v1/qrCodePush";
    }
}
