package com.fangke.visitor.device.pull;

import com.fangke.visitor.device.model.AccessEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新终端记录报文映射（docs/14 §6）：openType → identify_type、时间解析、脱敏、幂等键。
 */
class OpenApiRecordMapperTest {

    @Test
    @DisplayName("openType 映射：人脸/人证/IC/远程/二维码/密码/未知")
    void identifyTypeMapping() {
        assertEquals(OpenApiRecordMapper.IDENTIFY_FACE, OpenApiRecordMapper.identifyType(0));
        assertEquals(OpenApiRecordMapper.IDENTIFY_FACE, OpenApiRecordMapper.identifyType(1));
        assertEquals(OpenApiRecordMapper.IDENTIFY_ID_CARD, OpenApiRecordMapper.identifyType(2));
        assertEquals(OpenApiRecordMapper.IDENTIFY_IC, OpenApiRecordMapper.identifyType(3));
        assertEquals(OpenApiRecordMapper.IDENTIFY_MANUAL, OpenApiRecordMapper.identifyType(4));
        assertEquals(OpenApiRecordMapper.IDENTIFY_QR, OpenApiRecordMapper.identifyType(5));
        assertEquals(OpenApiRecordMapper.IDENTIFY_PASSWORD, OpenApiRecordMapper.identifyType(6));
        assertEquals(OpenApiRecordMapper.IDENTIFY_ID_CARD, OpenApiRecordMapper.identifyType(9));
        assertEquals(OpenApiRecordMapper.IDENTIFY_IC, OpenApiRecordMapper.identifyType(10));
        // 未来固件新增的未知值兜底为人脸，不能抛异常也不能乱码
        assertEquals(OpenApiRecordMapper.IDENTIFY_FACE, OpenApiRecordMapper.identifyType(99));
        assertEquals(OpenApiRecordMapper.IDENTIFY_FACE, OpenApiRecordMapper.identifyType(null));
    }

    @Test
    @DisplayName("识别方式文案")
    void identifyText() {
        assertEquals("二维码", OpenApiRecordMapper.identifyText(OpenApiRecordMapper.IDENTIFY_QR));
        assertEquals("密码", OpenApiRecordMapper.identifyText(OpenApiRecordMapper.IDENTIFY_PASSWORD));
    }

    @Test
    @DisplayName("时间解析：正常/空/非法都不抛异常")
    void eventTime() {
        assertEquals(LocalDateTime.of(2026, 11, 19, 9, 30, 15),
                OpenApiRecordMapper.eventTime("2026-11-19 09:30:15"));
        assertNull(OpenApiRecordMapper.eventTime(""));
        assertNull(OpenApiRecordMapper.eventTime("20261119"));
        assertNull(OpenApiRecordMapper.eventTime(null));
    }

    @Test
    @DisplayName("身份证号脱敏：设备传明文，落库前必须打码")
    void maskIdCard() {
        assertEquals("110101********0011", OpenApiRecordMapper.maskIdCard("110101199003070011"));
        assertEquals("****", OpenApiRecordMapper.maskIdCard("123"));
        assertNull(OpenApiRecordMapper.maskIdCard(null));
    }

    @Test
    @DisplayName("幂等键：tableID 优先，其次 id，否则用设备+时间+证件兜底")
    void eventUuidPriority() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 19, 9, 30);
        assertEquals("T1001", OpenApiRecordMapper.eventUuid("1001", "20261119093000", "SN1", time, "110101"));
        assertEquals("D20261119093000", OpenApiRecordMapper.eventUuid(null, "20261119093000", "SN1", time, "110101"));
        String fallback = OpenApiRecordMapper.eventUuid(null, null, "SN1", time, "110101");
        assertTrue(fallback.startsWith("XSN1-"));
        assertNotEquals(fallback, OpenApiRecordMapper.eventUuid(null, null, "SN2", time, "110101"));
    }

    @Test
    @DisplayName("组装事件：方向按 inout、失败带原因、证件号已脱敏")
    void toEvent() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 19, 9, 30);
        AccessEvent ok = OpenApiRecordMapper.toEvent("SN1", 1L, 1L, 0, 5, 1,
                "110101199003070011", "张三", "V1", time, "36.5", "{}");
        assertEquals(0, ok.direction(), "inout=0 表示出");
        assertEquals(OpenApiRecordMapper.IDENTIFY_QR, ok.identifyType());
        assertEquals(1, ok.resultStatus());
        assertEquals("110101********0011", ok.idCardMask());
        assertNull(ok.failReason());
        assertEquals("36.5", ok.temperature().toPlainString());
        assertEquals(1, ok.source());

        AccessEvent failed = OpenApiRecordMapper.toEvent("SN1", 1L, 1L, 1, 2, 0,
                null, null, null, time, null, "{}");
        assertEquals(1, failed.direction());
        assertEquals(0, failed.resultStatus());
        assertEquals("设备判定未通过", failed.failReason());
    }
}
