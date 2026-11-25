package com.fangke.visitor.vehicle.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * 云车场开放接口客户端（docs/19）。
 *
 * <p>所有接口同一个 URL {@code /thirdServer/v1/openApi}，靠 {@code service_name} 区分；
 * 每个请求都要 {@code park_id} + {@code sign}。凭据未配置时 {@link #configured()} 为 false，
 * 调用方只做 dry-run，不发起请求。
 */
@Component
public class CloudParkingClient {

    private static final Logger log = LoggerFactory.getLogger(CloudParkingClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties props;

    public CloudParkingClient(AppProperties props) {
        this.props = props;
    }

    private AppProperties.Vehicle.Cloud config() {
        return props.getVehicle().getCloud();
    }

    public boolean configured() {
        AppProperties.Vehicle.Cloud cloud = config();
        return cloud.isEnabled() && cloud.configured();
    }

    /**
     * 车辆访客预约（{@code visitor_sync}）：按车牌增/改/删。
     *
     * @param operateType 1 添加 / 2 修改 / 3 删除
     */
    public Map<String, Object> visitorSync(String plateNo, String visitorId, String personName, String mobile,
                                           LocalDateTime begin, LocalDateTime end,
                                           int operateType, String remark, String reserved) {
        AppProperties.Vehicle.Cloud cloud = config();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("service_name", "visitor_sync");
        params.put("park_id", cloud.getParkId());
        params.put("visitor_id", visitorId);
        params.put("plateNumber", plateNo);
        params.put("beginTime", DATETIME.format(begin));
        params.put("endTime", DATETIME.format(end));
        params.put("personName", personName == null ? "" : personName);
        if (mobile != null && !mobile.isBlank()) {
            params.put("mobile", mobile);
        }
        params.put("type", cloud.getVisitorType());
        if (cloud.getVisitorType() == 1) {
            params.put("channel_id", cloud.getChannelId());
        }
        params.put("operate_type", operateType);
        if (remark != null && !remark.isBlank()) {
            params.put("remark", remark);
        }
        params.put("createTime", DATETIME.format(LocalDateTime.now()));
        if (reserved != null && !reserved.isBlank()) {
            params.put("reservedStr", reserved);
        }
        return call(params);
    }

    /** 下发黑名单（{@code downBlackCar}）。 */
    public Map<String, Object> blackCar(String plateNo, String blackUuid, String remark,
                                        LocalDateTime begin, LocalDateTime end, int opType, String operator) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("service_name", "downBlackCar");
        params.put("park_id", config().getParkId());
        params.put("plateNumber", plateNo);
        params.put("remark", remark == null ? "" : remark);
        params.put("black_uuid", blackUuid);
        params.put("opType", opType);
        params.put("beginTime", DATETIME.format(begin));
        params.put("endTime", DATETIME.format(end));
        params.put("createName", operator == null ? "fangke" : operator);
        params.put("createTime", DATETIME.format(LocalDateTime.now()));
        return call(params);
    }

    /** 查询通道列表（{@code query_channels}），用于拿出口 channelId。 */
    public Map<String, Object> queryChannels() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("service_name", "query_channels");
        params.put("park_id", config().getParkId());
        return call(params);
    }

    /** 查询车辆类型列表（{@code query_authTypes}），员工车牌走月卡时需要。 */
    public Map<String, Object> queryAuthTypes() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("service_name", "query_authTypes");
        params.put("park_id", config().getParkId());
        return call(params);
    }

    /**
     * 统一调用：补 {@code park_id}/{@code sign} → POST JSON → 校验 {@code code=1}。
     *
     * @throws CloudParkingException 网络异常、HTTP 非 200、返回 code 非 1
     */
    public Map<String, Object> call(Map<String, Object> params) {
        AppProperties.Vehicle.Cloud cloud = config();
        if (!cloud.configured()) {
            throw new CloudParkingException("未配置车场接口地址/park_id/secretKey（docs/19 §3.1）");
        }
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("park_id", cloud.getParkId());
        body.put("sign", CloudParkingSign.sign(body, cloud.getSecret()));
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new CloudParkingException("请求序列化失败：" + e.getMessage(), e);
        }
        long start = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, cloud.getTimeoutSeconds())))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(cloud.getBaseUrl().trim()))
                    .timeout(Duration.ofSeconds(Math.max(1, cloud.getTimeoutSeconds())))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long cost = System.currentTimeMillis() - start;
            log.info("车场接口 service={} plate={} cost={}ms http={} resp={}",
                    params.get("service_name"), params.get("plateNumber"), cost,
                    response.statusCode(), abbreviate(response.body()));
            if (response.statusCode() != 200) {
                throw new CloudParkingException("车场接口 HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
            }
            JsonNode node = MAPPER.readTree(response.body());
            Map<String, Object> result = MAPPER.convertValue(node, Map.class);
            if (node.path("code").asInt(0) != 1) {
                throw new CloudParkingException("车场返回失败：" + node.path("msg").asText("(空)"));
            }
            return result;
        } catch (CloudParkingException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudParkingException("调用车场接口失败：" + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
