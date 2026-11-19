package com.fangke.visitor.device.callback;

import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.callback.dto.CompareResponse;
import com.fangke.visitor.device.callback.dto.HeartbeatPayload;
import com.fangke.visitor.device.callback.dto.RecordPayload;
import com.fangke.visitor.device.service.AccessRecordService;
import com.fangke.visitor.device.service.HeartbeatService;
import com.fangke.visitor.device.service.OnlineCompareService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备回调的<b>报文契约</b>测试：返回体一旦套上平台统一响应，设备就会无限重推。
 */
@WebMvcTest(controllers = DeviceCallbackController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = com.fangke.visitor.config.WebConfig.class))
@Import({DeviceCallbackGuard.class, DeviceCallbackContractTest.PropsConfig.class})
class DeviceCallbackContractTest {

    @TestConfiguration
    static class PropsConfig {
        @Bean
        AppProperties appProperties() {
            return new AppProperties();
        }

        /** AuthInterceptor 会被 WebMvcTest 一并加载，需要它的依赖。 */
        @Bean
        com.fangke.visitor.security.JwtService jwtService(AppProperties props) {
            return new com.fangke.visitor.security.JwtService(props);
        }
    }

    private static final String HEARTBEAT = """
            {"SN":"T77R781116ZS","currentTime":"1794793596","ip":"192.168.10.135","password":"123456","personCount":3}
            """;

    private static final String RECORD = """
            {"id":"evt-1","Mac_addr":"0A:0C:E8:68:11:16","SN":"T77R781116ZS",
             "time":"2026-11-16 09:47:31","inout":1,"employee_number":"V0001",
             "IdentifyType":"2","resultStatus":1,"face_base64":"/9j/4AAQ"}
            """;

    private static final String COMPARE = """
            {"dev_sno":"0A:0C:E8:68:11:16","deviceSN":"T77R781116ZS","type":"qr","value":"RV1.T01.A1.1794793596.8f3a1c.7d2e9b4f"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HeartbeatService heartbeatService;

    @MockBean
    private AccessRecordService accessRecordService;

    @MockBean
    private OnlineCompareService onlineCompareService;

    @Test
    @DisplayName("心跳返回体严格为 {\"message\":\"ok\",\"result\":0}，不含 code/data 包装")
    void heartbeatReturnsDeviceAck() throws Exception {
        mockMvc.perform(post("/api/device/callback/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HEARTBEAT))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"ok\",\"result\":0}", true))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("真实心跳报文能绑定：SN 是大写，不能被丢掉（否则设备查不到）")
    void heartbeatPayloadBindsSn() throws Exception {
        mockMvc.perform(post("/api/device/callback/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HEARTBEAT))
                .andExpect(status().isOk());

        ArgumentCaptor<HeartbeatPayload> captor = ArgumentCaptor.forClass(HeartbeatPayload.class);
        verify(heartbeatService).handle(captor.capture(), anyString());
        assertEquals("T77R781116ZS", captor.getValue().getSn());
        assertEquals(3, captor.getValue().getPersonCount());
        assertEquals(1794793596L, captor.getValue().epochSeconds());
    }

    @Test
    @DisplayName("真实记录报文能绑定：employee_number / IdentifyType / inout 都不能丢")
    void recordPayloadBindsFields() throws Exception {
        given(accessRecordService.handle(any(), anyString()))
                .willReturn(new AccessRecordService.Outcome(true, false, true, null));

        mockMvc.perform(post("/api/device/callback/record")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().isOk());

        ArgumentCaptor<RecordPayload> captor = ArgumentCaptor.forClass(RecordPayload.class);
        verify(accessRecordService).handle(captor.capture(), anyString());
        RecordPayload payload = captor.getValue();
        assertEquals("T77R781116ZS", payload.getSn());
        assertEquals("V0001", payload.getEmployeeNumber());
        assertEquals(2, payload.identifyTypeCode());
        assertEquals(1, payload.directionOrDefault());
        assertEquals(1, payload.resultStatusOrDefault());
    }

    @Test
    @DisplayName("心跳内部异常也必须回 ok（否则设备反复重试）")
    void heartbeatSwallowsError() throws Exception {
        willThrow(new IllegalStateException("db down"))
                .given(heartbeatService).handle(any(), anyString());

        mockMvc.perform(post("/api/device/callback/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HEARTBEAT))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"ok\",\"result\":0}", true));
    }

    @Test
    @DisplayName("记录入库失败回 result=1，让设备重推（宁可重复，不可丢失）")
    void recordFailureAsksRetry() throws Exception {
        willThrow(new IllegalStateException("db down"))
                .given(accessRecordService).handle(any(), anyString());

        mockMvc.perform(post("/api/device/callback/record")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().is5xxServerError())
                .andExpect(content().json("{\"message\":\"error\",\"result\":1}", true));
    }

    @Test
    @DisplayName("记录入库成功回 ok")
    void recordSuccess() throws Exception {
        given(accessRecordService.handle(any(), anyString()))
                .willReturn(new AccessRecordService.Outcome(true, false, true, null));

        mockMvc.perform(post("/api/device/callback/record")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"ok\",\"result\":0}", true));
    }

    @Test
    @DisplayName("路径别名：/heartBeat 与 /getrecord/ 也能被接收（现场脚本默认拼这两个）")
    void aliasPathsWork() throws Exception {
        given(accessRecordService.handle(any(), anyString()))
                .willReturn(new AccessRecordService.Outcome(true, false, true, null));

        mockMvc.perform(post("/api/device/callback/heartBeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HEARTBEAT))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"ok\",\"result\":0}", true));

        mockMvc.perform(post("/api/device/callback/getrecord/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"ok\",\"result\":0}", true));
    }

    @Test
    @DisplayName("在线比对接协议返回 status/msg/audio")
    void compareReturnsProtocolBody() throws Exception {
        given(onlineCompareService.compare(any())).willReturn(CompareResponse.pass("识别成功"));

        mockMvc.perform(post("/api/device/callback/compare/online")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPARE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.msg").value("识别成功"))
                .andExpect(jsonPath("$.audio").value("识别成功"));
    }

    @Test
    @DisplayName("在线比对内部异常：不给设备开门，并给出可见原因")
    void compareErrorDoesNotOpen() throws Exception {
        willThrow(new IllegalStateException("boom")).given(onlineCompareService).compare(any());

        mockMvc.perform(post("/api/device/callback/compare/online")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPARE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(CompareResponse.NO_GRANT));
    }
}
