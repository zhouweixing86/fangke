package com.fangke.visitor.device.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 局域网 HTTP 适配器：直连设备 8091（docs/06）。
 *
 * <p>已通过真机验证的接口：{@code /deviceLogin}、{@code /getDeviceParameter}、
 * {@code /setDeviceParameter}、{@code /addDeviceWhiteList}、{@code /setDeviceRemoteOpen}、
 * {@code /setDeviceShowMessage}、{@code /getAllDeviceIdWhiteList}、{@code /getDeviceVersion}。
 */
@Component
public class LanHttpAdapter implements DeviceAdapter {

    private static final Logger log = LoggerFactory.getLogger(LanHttpAdapter.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final AppProperties props;

    @org.springframework.beans.factory.annotation.Autowired
    public LanHttpAdapter(ObjectMapper mapper, AppProperties props) {
        this(mapper, props, HttpClient.newBuilder()
                .connectTimeout(props.getDevice().getConnectTimeout())
                .build());
    }

    /** 供测试注入自定义 HttpClient。 */
    LanHttpAdapter(ObjectMapper mapper, AppProperties props, HttpClient http) {
        this.mapper = mapper;
        this.props = props;
        this.http = http;
    }

    @Override
    public boolean supports(Device.Channel channel) {
        return channel == Device.Channel.LAN;
    }

    /**
     * 8091 局域网协议覆盖的能力（docs/06、docs/12）。
     *
     * <p>{@code FACE_PHOTO} 语义上协议支持（{@code register_base64}），但当前测试机固件
     * 的人脸下发未验证，换机上线前需用 docs/12 的自检清单复测。
     */
    @Override
    public Set<DeviceCapability> capabilities() {
        return EnumSet.of(
                DeviceCapability.PARAM_READ,
                DeviceCapability.PARAM_WRITE,
                DeviceCapability.PERSON_UPSERT,
                DeviceCapability.PERSON_REMOVE,
                DeviceCapability.PERSON_CLEAR,
                DeviceCapability.PERSON_LIST,
                DeviceCapability.VERSION_READ,
                DeviceCapability.DOOR_OPEN,
                DeviceCapability.SCREEN_MESSAGE,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.FACE_PHOTO,
                DeviceCapability.QR_ONLINE,
                DeviceCapability.HEARTBEAT,
                DeviceCapability.RECORD_CALLBACK);
    }

    /* ---------------- 参数 ---------------- */

    @Override
    public JsonNode readParams(Device device) {
        DeviceResult result = post(device, "getDeviceParameter", mapper.createObjectNode());
        return unwrap(result.data());
    }

    @Override
    public void writeParams(Device device, Map<String, Object> partial) {
        JsonNode current = readParams(device);
        if (!current.isObject()) {
            throw new DeviceException(ErrorCode.DEVICE_PARAM_ERROR, "设备参数回读格式异常，已中止下发");
        }
        ObjectNode merged = ((ObjectNode) current).deepCopy();
        partial.forEach((key, value) -> merged.set(key, mapper.valueToTree(value)));

        ObjectNode body = mapper.createObjectNode();
        body.set("data", merged);
        post(device, "setDeviceParameter", body);
    }

    /* ---------------- 名单 ---------------- */

    @Override
    public void upsertPerson(Device device, PersonPayload payload) {
        ObjectNode data = mapper.createObjectNode();
        data.put("usertype", payload.getUserType());
        data.put("employee_number", payload.getPersonNo());
        data.put("name", payload.getName() == null ? "" : payload.getName());
        data.put("idno", payload.getIdCard() == null ? "" : payload.getIdCard());
        data.put("passAlgo", !payload.requiresPhoto());
        data.put("TimeGroupId", payload.getTimeGroupId() == null ? 0 : payload.getTimeGroupId());
        if (payload.getSpecialGroupId() != null) {
            data.put("SpecialGroupId", payload.getSpecialGroupId());
        }
        if (payload.getValidFrom() != null) {
            data.put("peoplestartdate", DeviceDateFormats.format(payload.getValidFrom()));
        }
        if (payload.getValidTo() != null) {
            data.put("peopleenddate", DeviceDateFormats.format(payload.getValidTo()));
        }
        if (payload.requiresPhoto()) {
            data.put("register_base64", payload.getPhotoBase64());
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("totalnum", 1);
        body.put("currentnum", 1);
        body.set("data", data);
        post(device, "addDeviceWhiteList", body);
    }

    @Override
    public void removePerson(Device device, String personNo, String userType) {
        ObjectNode data = mapper.createObjectNode();
        data.put("employee_number", personNo);
        data.put("usertype", userType == null ? PersonPayload.USER_TYPE_VISITOR : userType);

        ObjectNode body = mapper.createObjectNode();
        body.set("data", data);
        post(device, "deleteDeviceWhiteList", body);
    }

    @Override
    public void clearAllPersons(Device device) {
        post(device, "deleteDeviceAllWhiteList", mapper.createObjectNode());
    }

    @Override
    public List<String> listPersonNos(Device device) {
        DeviceResult result = post(device, "getAllDeviceIdWhiteList", mapper.createObjectNode());
        JsonNode data = unwrap(result.data());
        JsonNode idList = data.path("idList");
        List<String> nos = new ArrayList<>();
        if (idList.isArray()) {
            for (JsonNode node : idList) {
                nos.add(node.asText());
            }
        }
        return nos;
    }

    /* ---------------- 运行信息 ---------------- */

    @Override
    public JsonNode readVersion(Device device) {
        DeviceResult result = post(device, "getDeviceVersion", mapper.createObjectNode());
        return unwrap(result.data());
    }

    @Override
    public void openDoor(Device device) {
        post(device, "setDeviceRemoteOpen", mapper.createObjectNode());
    }

    @Override
    public void showMessage(Device device, String message) {
        ObjectNode data = mapper.createObjectNode();
        data.put("message", message);
        ObjectNode body = mapper.createObjectNode();
        body.set("data", data);
        post(device, "setDeviceShowMessage", body);
    }

    @Override
    public void setDeviceTime(Device device, LocalDateTime time) {
        ObjectNode data = mapper.createObjectNode();
        data.put("time", DATETIME.format(time));
        ObjectNode body = mapper.createObjectNode();
        body.set("data", data);
        post(device, "setDeviceTime", body);
    }

    /* ---------------- 内部 ---------------- */

    private DeviceResult post(Device device, String path, ObjectNode body) {
        if (device.getIp() == null || device.getIp().isBlank()) {
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "设备未配置 IP：" + device.getDeviceSn());
        }
        ObjectNode payload = body.deepCopy();
        payload.put("password", resolvePassword(device));

        String url = device.baseUrl() + "/" + path;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(props.getDevice().getRequestTimeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE,
                    "调用设备失败 " + path + "：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "调用设备被中断 " + path, e);
        }

        if (response.statusCode() != 200) {
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE,
                    "设备返回 HTTP " + response.statusCode() + "（" + path + "）");
        }

        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "设备返回非 JSON：" + path, e);
        }

        int code = root.path("result").asInt(-1);
        String message = root.path("message").asText("");
        if (code == 1) {
            throw new DeviceException(ErrorCode.DEVICE_PARAM_ERROR, "设备参数错误：" + message);
        }
        if (code == 2) {
            throw new DeviceException(ErrorCode.DEVICE_PASSWORD, "设备密码错误：" + message);
        }
        if (code != 0) {
            log.warn("设备返回非 0 结果 path={} code={} message={}", path, code, message);
        }
        return new DeviceResult(code, message, root.get("data"));
    }

    /**
     * 部分固件把 data 序列化成字符串（如 getAllDeviceIdWhiteList 返回 {@code "{\"idList\":[...]}"}），
     * 这里统一解包成对象，避免上层重复判断。
     */
    private JsonNode unwrap(JsonNode data) {
        if (data == null || data.isNull()) {
            return mapper.createObjectNode();
        }
        if (data.isTextual()) {
            try {
                return mapper.readTree(data.asText());
            } catch (IOException e) {
                return mapper.createObjectNode();
            }
        }
        return data;
    }

    private String resolvePassword(Device device) {
        String pwd = device.getApiPassword();
        return (pwd == null || pwd.isBlank()) ? props.getDevice().getDefaultPassword() : pwd;
    }

    /** 便于排障：把 map 中的键排序输出（仅日志用）。 */
    static String describe(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        Iterator<String> it = map.keySet().stream().sorted().iterator();
        while (it.hasNext()) {
            String key = it.next();
            sb.append(key).append('=').append(map.get(key)).append(';');
        }
        return sb.append('}').toString();
    }

    static Duration defaultTimeout(AppProperties props) {
        return props.getDevice().getRequestTimeout();
    }
}
