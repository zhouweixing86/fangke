package com.fangke.visitor.device.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.PersonPayload;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 HTTP 服务模拟设备 8091 接口，验证适配层的报文格式与错误映射。
 */
class LanHttpAdapterTest {

    private HttpServer server;
    private Device device;
    private LanHttpAdapter adapter;

    /** 记录各接口收到的请求体，便于断言。 */
    private final Map<String, AtomicReference<String>> received = new ConcurrentHashMap<>();
    /** 可按接口定制返回体。 */
    private final Map<String, String> responses = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        responses.put("/getDeviceParameter",
                "{\"message\":\"ok\",\"result\":0,\"data\":{\"recogModeID\":3,\"whitevalue\":70,\"Comparison\":0}}");
        responses.put("/setDeviceParameter", "{\"message\":\"ok\",\"result\":0}");
        responses.put("/addDeviceWhiteList", "{\"message\":\"添加成功\",\"result\":0}");
        responses.put("/deleteDeviceWhiteList", "{\"message\":\"ok\",\"result\":0}");
        responses.put("/getAllDeviceIdWhiteList",
                "{\"message\":\"ok\",\"result\":0,\"data\":\"{\\n\\t\\\"idList\\\" : [\\\"V0001\\\",\\\"V0002\\\"]\\n}\"}");
        responses.put("/deviceLogin", "{\"message\":\"Login Success\",\"result\":0}");

        server.createContext("/", this::handle);
        server.start();

        AppProperties props = new AppProperties();
        device = new Device();
        device.setDeviceSn("T77R781116ZS");
        device.setIp("127.0.0.1");
        device.setPort(server.getAddress().getPort());
        device.setChannel(Device.Channel.LAN);
        adapter = new LanHttpAdapter(new ObjectMapper(), props);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.computeIfAbsent(path, k -> new AtomicReference<>()).set(body);
        String response = responses.getOrDefault(path, "{\"message\":\"ok\",\"result\":0}");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String bodyOf(String path) {
        AtomicReference<String> ref = received.get(path);
        return ref == null ? "" : ref.get();
    }

    @Test
    @DisplayName("访客下发：passAlgo=true、日期带尾随空格、带口令")
    void upsertVisitorSendsExpectedBody() {
        PersonPayload payload = new PersonPayload();
        payload.setPersonNo("V202611160001");
        payload.setName("张三");
        payload.setIdCard("110101199001011234");
        payload.setUserType(PersonPayload.USER_TYPE_VISITOR);
        payload.setValidFrom(LocalDate.of(2026, 11, 16));
        payload.setValidTo(LocalDate.of(2026, 11, 16));

        adapter.upsertPerson(device, payload);

        String body = bodyOf("/addDeviceWhiteList");
        assertTrue(body.contains("\"employee_number\":\"V202611160001\""), body);
        assertTrue(body.contains("\"peoplestartdate\":\"2026-11-16 \""), body);
        assertTrue(body.contains("\"peopleenddate\":\"2026-11-16 \""), body);
        assertTrue(body.contains("\"passAlgo\":true"), body);
        assertTrue(body.contains("\"password\":\"123456\""), body);
        assertTrue(body.contains("\"totalnum\":1"), body);
    }

    @Test
    @DisplayName("员工下发：带照片时 passAlgo=false 且带 register_base64")
    void upsertEmployeeSendsPhoto() {
        PersonPayload payload = new PersonPayload();
        payload.setPersonNo("E000123");
        payload.setName("王五");
        payload.setUserType(PersonPayload.USER_TYPE_WHITE);
        payload.setPhotoBase64("/9j/4AAQSkZJRg==");

        adapter.upsertPerson(device, payload);

        String body = bodyOf("/addDeviceWhiteList");
        assertTrue(body.contains("\"passAlgo\":false"), body);
        assertTrue(body.contains("\"register_base64\":\"/9j/4AAQSkZJRg==\""), body);
    }

    @Test
    @DisplayName("参数下发：先取全量再合并，未改字段不丢")
    void writeParamsMergesExisting() {
        adapter.writeParams(device, Map.of("recogModeID", 4));

        String body = bodyOf("/setDeviceParameter");
        assertTrue(body.contains("\"recogModeID\":4"), body);
        // 回读到的旧字段必须原样保留
        assertTrue(body.contains("\"whitevalue\":70"), body);
        assertTrue(body.contains("\"Comparison\":0"), body);
    }

    @Test
    @DisplayName("密码错误映射为 4002，参数错误映射为 4003")
    void mapsDeviceErrorCodes() {
        responses.put("/addDeviceWhiteList", "{\"message\":\"password error\",\"result\":2}");
        PersonPayload payload = new PersonPayload();
        payload.setPersonNo("V1");

        DeviceException pwdError = assertThrows(DeviceException.class,
                () -> adapter.upsertPerson(device, payload));
        assertEquals(ErrorCode.DEVICE_PASSWORD.code(), pwdError.getCode());

        responses.put("/addDeviceWhiteList", "{\"message\":\"param error\",\"result\":1}");
        DeviceException paramError = assertThrows(DeviceException.class,
                () -> adapter.upsertPerson(device, payload));
        assertEquals(ErrorCode.DEVICE_PARAM_ERROR.code(), paramError.getCode());
    }

    @Test
    @DisplayName("名单列表：设备把 data 序列化成字符串时也能解析")
    void listPersonNosParsesTextualData() {
        List<String> nos = adapter.listPersonNos(device);
        assertEquals(List.of("V0001", "V0002"), nos);
    }
}
