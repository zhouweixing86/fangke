package com.fangke.visitor.device.service;

import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.callback.dto.ComparePayload;
import com.fangke.visitor.device.callback.dto.CompareResponse;
import com.fangke.visitor.device.model.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OnlineCompareServiceTest {

    private static final String APPOINTMENT = "A2026111700001";

    private InMemoryDeviceRepository deviceRepository;
    private QrCodeVerifier verifier;
    private MutableGrantChecker grantChecker;
    private OnlineCompareService service;

    /** 可切换状态的授权检查桩：避免单测依赖数据库。 */
    static class MutableGrantChecker implements GrantChecker {
        State state = State.OK;
        String lastAppointmentNo;

        @Override
        public State check(String appointmentNo, Long gateId, Instant now) {
            this.lastAppointmentNo = appointmentNo;
            return state;
        }
    }

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getQr().setSecret("unit-test-secret");

        Device device = new Device();
        device.setId(1L);
        device.setDeviceSn("T77R781116ZS");
        device.setMac("0A:0C:E8:68:11:16");
        device.setGateId(1L);
        deviceRepository = new InMemoryDeviceRepository().put(device);

        verifier = new QrCodeVerifier(props);
        grantChecker = new MutableGrantChecker();
        service = new OnlineCompareService(deviceRepository, verifier, grantChecker);
    }

    private ComparePayload qrPayload(String code) {
        ComparePayload p = new ComparePayload();
        p.setDeviceSn("T77R781116ZS");
        p.setDevSno("0A:0C:E8:68:11:16");
        p.setType("qr");
        p.setValue(code);
        return p;
    }

    private String validCode() {
        long exp = Instant.now().getEpochSecond() + 60;
        String sig = verifier.sign("T01." + APPOINTMENT + "." + exp + ".8f3a1c");
        return "RV1.T01." + APPOINTMENT + "." + exp + ".8f3a1c." + sig;
    }

    @Test
    @DisplayName("有效码 + 有授权：status=0 开门")
    void passQr() {
        CompareResponse response = service.compare(qrPayload(validCode()));

        assertEquals(0, response.status());
        assertEquals(APPOINTMENT, grantChecker.lastAppointmentNo);
    }

    @Test
    @DisplayName("无授权：status=4，不开门")
    void noGrant() {
        grantChecker.state = GrantChecker.State.NO_GRANT;
        assertEquals(CompareResponse.NO_GRANT, service.compare(qrPayload(validCode())).status());
    }

    @Test
    @DisplayName("不在授权时段：status=5")
    void outOfWindow() {
        grantChecker.state = GrantChecker.State.OUT_OF_WINDOW;
        assertEquals(CompareResponse.OUT_OF_WINDOW, service.compare(qrPayload(validCode())).status());
    }

    @Test
    @DisplayName("门点不符：status=6")
    void gateMismatch() {
        grantChecker.state = GrantChecker.State.GATE_MISMATCH;
        assertEquals(CompareResponse.GATE_MISMATCH, service.compare(qrPayload(validCode())).status());
    }

    @Test
    @DisplayName("过期码：status=3，提示刷新")
    void expiredQr() {
        long exp = Instant.now().getEpochSecond() - 5;
        String sig = verifier.sign("T01." + APPOINTMENT + "." + exp + ".8f3a1c");
        String code = "RV1.T01." + APPOINTMENT + "." + exp + ".8f3a1c." + sig;

        CompareResponse response = service.compare(qrPayload(code));
        assertEquals(CompareResponse.EXPIRED, response.status());
        assertEquals("二维码已过期，请刷新", response.msg());
    }

    @Test
    @DisplayName("未登记设备：直接拒绝")
    void unknownDevice() {
        ComparePayload p = qrPayload(validCode());
        p.setDeviceSn("UNKNOWN");
        p.setDevSno("FF:FF:FF:FF:FF:FF");

        assertEquals(CompareResponse.NO_GRANT, service.compare(p).status());
    }

    @Test
    @DisplayName("人证/人脸类型暂未接入时给出明确原因，而不是静默放行")
    void unsupportedTypesRejected() {
        ComparePayload p = qrPayload(validCode());
        p.setType("id");
        CompareResponse response = service.compare(p);

        assertEquals(CompareResponse.NO_GRANT, response.status());
        assertEquals("身份证核验未接入", response.msg());
    }
}
