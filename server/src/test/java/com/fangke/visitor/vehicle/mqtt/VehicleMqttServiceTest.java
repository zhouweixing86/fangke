package com.fangke.visitor.vehicle.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.PlatePayload;
import com.fangke.visitor.vehicle.service.VehicleAccessService;
import com.fangke.visitor.vehicle.service.VehicleDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 车闸 MQTT 报文：按厂商《信路通路外停车 MQTT 协议接口规范 V14》（docs/23）。
 *
 * <p>公共报文头 {@code {command, data, requestId, time, version, sn}}，且 {@code data} 必须是
 * 「先序列化为字符串」的 JSON；白名单在协议里叫「固定车 CarInfo」。
 */
class VehicleMqttServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SN = "COPCM-E10-LS26050994";

    private VehicleMqttGateway gateway;
    private VehicleMqttLogRepository logRepository;
    private VehicleAccessService accessService;
    private VehicleDeviceService deviceService;
    private VehicleMqttService service;

    @BeforeEach
    void setUp() {
        gateway = mock(VehicleMqttGateway.class);
        logRepository = mock(VehicleMqttLogRepository.class);
        accessService = mock(VehicleAccessService.class);
        deviceService = mock(VehicleDeviceService.class);
        service = new VehicleMqttService(new AppProperties(), gateway, logRepository, accessService, deviceService);
    }

    private static JsonNode envelope(String payload) throws Exception {
        JsonNode node = MAPPER.readTree(payload);
        assertEquals(SN, node.path("sn").asText());
        assertEquals("1.0.1", node.path("version").asText());
        assertFalse(node.path("requestId").asText().isBlank());
        assertTrue(node.path("time").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertTrue(node.path("data").isTextual(), "data 必须是字符串，不是对象");
        return node;
    }

    @Test
    @DisplayName("下发车牌：AddCarInfo(V1) + carInfoList(plate/type/time=到期日)")
    void addPlateUsesAddCarInfoV1() throws Exception {
        service.addPlate(SN, "苏A12345",
                LocalDateTime.of(2026, 11, 25, 0, 0),
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway).publish(eq(SN), payload.capture());

        JsonNode node = envelope(payload.getValue());
        assertEquals("AddCarInfo", node.path("command").asText());
        JsonNode car = MAPPER.readTree(node.path("data").asText()).path("carInfoList").get(0);
        assertEquals("苏A12345", car.path("plate").asText());
        assertEquals(1, car.path("type").asInt());
        assertEquals("2026-12-31", car.path("time").asText());
    }

    @Test
    @DisplayName("删除走 DeleteCarInfo，全量对齐走批量 AddCarInfo")
    void deleteAndImportUseProtocolCommands() throws Exception {
        service.deletePlate(SN, "苏A12345");
        service.importPlates(SN, List.of(new PlatePayload("苏A12345", PlatePayload.OWNER_VISITOR, 1L, "张三", null,
                LocalDateTime.of(2026, 11, 25, 0, 0), LocalDateTime.of(2026, 12, 31, 23, 59, 59))));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(2)).publish(eq(SN), payload.capture());

        JsonNode deleted = envelope(payload.getAllValues().get(0));
        assertEquals("DeleteCarInfo", deleted.path("command").asText());
        assertEquals("苏A12345",
                MAPPER.readTree(deleted.path("data").asText()).path("plateList").get(0).asText());

        JsonNode imported = envelope(payload.getAllValues().get(1));
        assertEquals("AddCarInfo", imported.path("command").asText());
        JsonNode list = MAPPER.readTree(imported.path("data").asText()).path("carInfoList");
        assertEquals(1, list.size());
        assertEquals("苏A12345", list.get(0).path("plate").asText());
        assertEquals("2026-12-31", list.get(0).path("time").asText());
    }

    @Test
    @DisplayName("上行 Result 的 carNO 能解析成车牌并落通行记录")
    void uploadResultParsesCarNo() {
        String upload = "{\"command\":\"Result\",\"data\":\"{\\\"carNO\\\":\\\"苏A12345\\\",\\\"carNOC\\\":\\\"蓝\\\","
                + "\\\"confid\\\":885,\\\"openGate\\\":0}\",\"requestId\":\"1\",\"sn\":\"" + SN
                + "\",\"time\":\"2026-11-25 23:39:45\",\"version\":\"1.0.1\"}";
        when(accessService.judge(eq("苏A12345"), any(), any()))
                .thenReturn(new VehicleAccessService.Decision(true, "白名单有效", null, null, null, null));

        service.onUpload("upload/" + SN, upload);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logRepository).save(eq("UP"), eq(SN), eq("upload/" + SN), eq(upload), message.capture());
        assertTrue(message.getValue().contains("苏A12345"), message.getValue());
        assertTrue(message.getValue().contains("放行"), message.getValue());
    }

    @Test
    @DisplayName("心跳/无车图片不落日志表；Conn 上行时自动使能下行回执；回执报文标成功")
    void heartbeatConnAndReplyAreDescribed() {
        String heartbeat = "{\"command\":\"Rtd\",\"data\":\"{\\\"hasCar\\\":2}\",\"requestId\":\"2\",\"sn\":\"" + SN
                + "\",\"time\":\"2026-11-25 23:39:50\",\"version\":\"1.0.1\"}";
        service.onUpload("upload/" + SN, heartbeat);
        verify(logRepository, never()).save(any(), any(), any(), any(), any());

        String image = "{\"command\":\"Image\",\"data\":\"\",\"requestId\":\"5\",\"sn\":\"" + SN
                + "\",\"time\":\"2026-11-25 23:39:51\",\"version\":\"1.0.1\"}";
        service.onUpload("upload/" + SN, image);
        verify(logRepository, never()).save(any(), any(), any(), any(), any());

        String conn = "{\"command\":\"Conn\",\"data\":\"{\\\"deviceType\\\":\\\"PCM500FSPUTV14\\\"}\","
                + "\"requestId\":\"3\",\"sn\":\"" + SN + "\",\"time\":\"2026-11-25 23:40:00\",\"version\":\"1.0.1\"}";
        service.onUpload("upload/" + SN, conn);
        ArgumentCaptor<String> down = ArgumentCaptor.forClass(String.class);
        // EnableReply 走独立线程发（不能在回调线程里 publish），所以用 timeout 等它
        verify(gateway, timeout(3000)).publish(eq(SN), down.capture());
        assertTrue(down.getValue().contains("EnableReply"), down.getValue());
        assertTrue(down.getValue().contains("enable"), down.getValue());

        String ack = "{\"command\":\"AddCarInfo\",\"data\":\"{\\\"errCode\\\":0,\\\"errInfo\\\":\\\"\\\"}\","
                + "\"requestId\":\"4\",\"sn\":\"" + SN + "\",\"time\":\"2026-11-25 23:41:00\",\"version\":\"1.0.1\"}";
        service.onUpload("upload/" + SN, ack);
        verify(logRepository).save(eq("UP"), eq(SN), eq("upload/" + SN), eq(ack), eq("下行回执：成功"));
    }
}
