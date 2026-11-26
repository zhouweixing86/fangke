package com.fangke.visitor.vehicle.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.PlatePayload;
import com.fangke.visitor.vehicle.service.VehicleAccessService;
import com.fangke.visitor.vehicle.service.VehicleDeviceService;
import com.fangke.visitor.vehicle.service.VehiclePlateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private VehiclePlateService plateService;
    private VehicleMqttPlateStateRepository stateRepository;
    private VehicleMqttService service;

    @BeforeEach
    void setUp() {
        gateway = mock(VehicleMqttGateway.class);
        logRepository = mock(VehicleMqttLogRepository.class);
        accessService = mock(VehicleAccessService.class);
        deviceService = mock(VehicleDeviceService.class);
        plateService = mock(VehiclePlateService.class);
        stateRepository = mock(VehicleMqttPlateStateRepository.class);
        when(plateService.snapshot(any())).thenReturn(List.of());
        when(stateRepository.fingerprints(any())).thenReturn(new java.util.LinkedHashMap<>());
        service = new VehicleMqttService(new AppProperties(), gateway, logRepository, accessService,
                deviceService, plateService, stateRepository);
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
    @DisplayName("车牌里的引号/花括号不会拼坏下发报文")
    void plateWithQuoteKeepsPayloadValid() throws Exception {
        service.addPlate(SN, "苏A\"1{2}\\345",
                LocalDateTime.of(2026, 11, 25, 0, 0),
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway).publish(eq(SN), payload.capture());

        JsonNode node = envelope(payload.getValue());
        JsonNode car = MAPPER.readTree(node.path("data").asText()).path("carInfoList").get(0);
        assertEquals("苏A12345", car.path("plate").asText());
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
    @DisplayName("事件时间用相机 triggT/imgT（离线补传不串日期），异常值回落服务器时间")
    void eventTimeUsesCameraTimestamp() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDate yesterday = now.toLocalDate().minusDays(1);
        String ymd = yesterday.toString();

        String withTrigg = "{\"data\":\"{\\\"triggT\\\":\\\"" + ymd + " 23:55:00\\\"}\"}";
        assertEquals(yesterday.atTime(23, 55), VehicleMqttService.eventTimeOf(withTrigg));

        String future = "{\"data\":\"{\\\"triggT\\\":\\\"2099-01-01 00:00:00\\\"}\"}";
        assertTrue(VehicleMqttService.eventTimeOf(future).isAfter(now.minusMinutes(1)), "未来时间应回落 now");

        String compact = "{\"data\":\"{\\\"imgT\\\":\\\"" + ymd.replace("-", "") + "235500\\\"}\"}";
        assertEquals(yesterday.atTime(23, 55), VehicleMqttService.eventTimeOf(compact));

        String none = "{\"data\":\"{}\"}";
        assertTrue(VehicleMqttService.eventTimeOf(none).isAfter(now.minusMinutes(1)));
    }

    @Test
    @DisplayName("事件幂等键：有 guid 用 guid（重传去重），没有才随机")
    void eventUuidUsesGuid() {
        String guid = "e4263270-ab37-4108-b563-02195b0235a8";
        assertEquals("MQTT-E4263270-AB37-4108-B563-02195B0235A8",
                VehicleMqttService.eventUuidOf("{\"data\":\"{\\\"guid\\\":\\\"" + guid + "\\\"}\"}"));
        String random = VehicleMqttService.eventUuidOf("{\"data\":\"{}\"}");
        assertTrue(random.startsWith("MQTT"));
        assertEquals(24, random.length());
    }

    @Test
    @DisplayName("相机置信度 0-1000 折算成 0-100，并做边界收敛")
    void confidenceIsNormalized() {
        assertEquals(91, VehicleMqttService.confidenceOf("{\"data\":\"{\\\"confid\\\":912}\"}"));
        assertEquals(100, VehicleMqttService.confidenceOf("{\"data\":\"{\\\"confid\\\":1000}\"}"));
        assertEquals(100, VehicleMqttService.confidenceOf("{\"data\":\"{\\\"confid\\\":99999}\"}"));
        assertNull(VehicleMqttService.confidenceOf("{\"data\":\"{}\"}"));
        assertNull(VehicleMqttService.confidenceOf("{\"data\":\"{\\\"confid\\\":\\\"abc\\\"}\"}"));
    }

    @Test
    @DisplayName("字段叫 confidence 时兼容：≤100 当百分比，>100 仍按 0-1000 折算")
    void confidenceFieldIsTolerated() {
        assertEquals(95, VehicleMqttService.confidenceOf("{\"data\":\"{\\\"confidence\\\":95}\"}"));
        assertEquals(95, VehicleMqttService.confidenceOf("{\"data\":\"{\\\"confidence\\\":950}\"}"));
        assertEquals(91, VehicleMqttService.confidenceOf("{\"data\":\"{\\\"confid\\\":912,\\\"confidence\\\":5}\"}"));
    }

    @Test
    @DisplayName("手工下发/删除车牌也会同步对账状态，避免下一次对账把它当「未知状态」")
    void manualPlateOpsSyncState() {
        service.addPlate(SN, "苏A1", java.time.LocalDateTime.of(2026, 11, 26, 0, 0),
                java.time.LocalDateTime.of(2026, 12, 31, 23, 59));
        verify(stateRepository).upsert(eq(SN), eq("苏A1"), org.mockito.ArgumentMatchers.startsWith("1|2026-11-26"));

        service.deletePlate(SN, "苏A1");
        verify(stateRepository).remove(SN, "苏A1");
    }

    @Test
    @DisplayName("车牌对账：新增→AddCarInfo、内容变了→重发、清单里没有→DeleteCarInfo")
    void syncPushesAddsUpdatesAndDeletes() {
        LocalDateTime begin = LocalDateTime.of(2026, 11, 26, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 12, 31, 23, 59, 59);
        String fingerprint = "1|2026-11-26 00:00:00|2026-12-31 23:59:59";

        PlatePayload fresh = new PlatePayload("苏A11111", PlatePayload.OWNER_EMPLOYEE, 1L, "张三", "E0001", begin, end);
        PlatePayload same = new PlatePayload("苏A22222", PlatePayload.OWNER_EMPLOYEE, 2L, "李四", "E0002", begin, end);
        PlatePayload changed = new PlatePayload("苏A33333", PlatePayload.OWNER_VISITOR, 3L, "王五", null, begin, end);

        when(plateService.snapshot(null)).thenReturn(List.of(fresh, same, changed));
        java.util.Map<String, String> existing = new java.util.LinkedHashMap<>();
        existing.put("苏A22222", fingerprint);
        existing.put("苏A33333", "old-fingerprint");
        existing.put("苏A99999", fingerprint);
        when(stateRepository.fingerprints(SN)).thenReturn(existing);

        java.util.Map<String, Object> outcome = service.syncDevicePlates(SN, null);

        assertEquals(1, outcome.get("added"));
        assertEquals(1, outcome.get("updated"));
        assertEquals(1, outcome.get("removed"));
        assertEquals(1, outcome.get("unchanged"));
        // 3 次下行：新增、更新、删除
        verify(gateway, times(3)).publish(eq(SN), org.mockito.ArgumentMatchers.anyString());
        verify(stateRepository).upsert(SN, "苏A11111", fingerprint);
        verify(stateRepository).remove(SN, "苏A99999");
    }

    @Test
    @DisplayName("强制同步：先 ClearData 清空相机名单，再全量重推并补回放行规则")
    void forceResyncClearsThenRepushes() {
        com.fangke.visitor.device.model.Device online = new com.fangke.visitor.device.model.Device();
        online.setDeviceSn(SN);
        online.setOnline(true);
        online.setGateId(1L);
        online.setLastHeartbeat(java.time.LocalDateTime.now());
        when(deviceService.list(null)).thenReturn(List.of(online));
        when(plateService.snapshot(1L)).thenReturn(List.of(new PlatePayload("苏A1",
                PlatePayload.OWNER_EMPLOYEE, 1L, "张三", "E1",
                java.time.LocalDateTime.of(2026, 11, 26, 0, 0),
                java.time.LocalDateTime.of(2026, 12, 31, 23, 59))));

        java.util.Map<String, Object> result = service.forceResync(SN);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(3)).publish(eq(SN), payload.capture());
        java.util.List<String> sent = payload.getAllValues();
        assertTrue(sent.get(0).contains("ClearData"), sent.get(0));
        assertTrue(sent.get(0).contains("clearCarInfo"), sent.get(0));
        assertTrue(sent.get(1).contains("AddCarInfo") && sent.get(1).contains("苏A1"), sent.get(1));
        assertTrue(sent.get(2).contains("DlPassRule"), sent.get(2));
        verify(stateRepository).removeDevice(SN);
        assertNotNull(result.get("repushed"));
    }

    @Test
    @DisplayName("人工开闸：设备不存在或相机离线时明确报错，不假装成功")
    void openGateRejectsUnknownOrOfflineDevice() {
        com.fangke.visitor.device.model.Device offline = new com.fangke.visitor.device.model.Device();
        offline.setDeviceSn(SN);
        offline.setOnline(true);
        offline.setLastHeartbeat(java.time.LocalDateTime.now().minusMinutes(10));

        when(deviceService.list(null)).thenReturn(List.of(offline));
        assertThrows(com.fangke.visitor.common.BusinessException.class,
                () -> service.openGate(SN, "苏A1", "admin"), "离线相机应拒绝开闸");

        when(deviceService.list(null)).thenReturn(List.of());
        assertThrows(com.fangke.visitor.common.BusinessException.class,
                () -> service.openGate("NOT-EXIST", null, "admin"), "未知设备应拒绝");
    }

    @Test
    @DisplayName("上行 Result 的 carNO 能解析成车牌并落通行记录")
    void uploadResultParsesCarNo() {
        String upload = "{\"command\":\"Result\",\"data\":\"{\\\"carNO\\\":\\\"苏A12345\\\",\\\"carNOC\\\":\\\"蓝\\\","
                + "\\\"confid\\\":885,\\\"openGate\\\":0}\",\"requestId\":\"1\",\"sn\":\"" + SN
                + "\",\"time\":\"2026-11-25 23:39:45\",\"version\":\"1.0.1\"}";
        when(accessService.judgeGroup(eq("苏A12345"), any(), any()))
                .thenReturn(List.of(new VehicleAccessService.Decision(true, "白名单有效", null, null, null, null)));

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
        // 配置开关 + 放行规则都走独立线程发（不能在回调线程里 publish），所以用 timeout 等它
        verify(gateway, timeout(3000).atLeast(2)).publish(eq(SN), down.capture());
        assertTrue(down.getAllValues().stream().anyMatch(v -> v.contains("\"Config\"") && v.contains("enableOffline")),
                down.getAllValues().toString());
        assertTrue(down.getAllValues().stream().anyMatch(v -> v.contains("DlPassRule")),
                down.getAllValues().toString());

        String ack = "{\"command\":\"AddCarInfo\",\"data\":\"{\\\"errCode\\\":0,\\\"errInfo\\\":\\\"\\\"}\","
                + "\"requestId\":\"4\",\"sn\":\"" + SN + "\",\"time\":\"2026-11-25 23:41:00\",\"version\":\"1.0.1\"}";
        service.onUpload("upload/" + SN, ack);
        verify(logRepository).save(eq("UP"), eq(SN), eq("upload/" + SN), eq(ack), eq("下行回执：成功"));
    }

    @Test
    @DisplayName("放行规则：白名单全启用（相机自己开闸），临时车/黑名单不自动放行")
    void passRuleMakesWhitelistAutoOpen() throws Exception {
        service.applyPassRule(SN);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway).publish(eq(SN), payload.capture());

        JsonNode node = envelope(payload.getValue());
        assertEquals("DlPassRule", node.path("command").asText());
        JsonNode rule = MAPPER.readTree(node.path("data").asText());
        assertEquals(3, rule.path("wcarTiming").asInt());
        assertEquals(2, rule.path("tcarTiming").asInt());
        assertEquals(2, rule.path("bcarTiming").asInt());
        assertEquals(1, MAPPER.readTree(rule.path("wcarAct").asText()).path("action").asInt());
        assertEquals(0, MAPPER.readTree(rule.path("tcarAct").asText()).path("action").asInt());
        assertEquals(0, MAPPER.readTree(rule.path("bcarAct").asText()).path("action").asInt());
    }

    @Test
    @DisplayName("相机拒绝下行（errCode=1）时清空对账状态，下一轮会整体重推")
    void failedAckDropsPlateState() {
        String nack = "{\"command\":\"DeleteCarInfo\",\"data\":\"{\\\"errCode\\\":1,\\\"errInfo\\\":\\\"White name list process busy.\\\"}\","
                + "\"requestId\":\"9\",\"sn\":\"" + SN + "\",\"time\":\"2026-11-26 02:01:37\",\"version\":\"1.0.1\"}";
        service.onUpload("upload/" + SN, nack);

        verify(stateRepository, timeout(3000)).removeDevice(SN);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logRepository).save(eq("UP"), eq(SN), eq("upload/" + SN), eq(nack), message.capture());
        assertTrue(message.getValue().contains("失败"), message.getValue());
        assertTrue(message.getValue().contains("White name list process busy"), message.getValue());
    }
}
