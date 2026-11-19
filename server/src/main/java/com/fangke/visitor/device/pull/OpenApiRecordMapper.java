package com.fangke.visitor.device.pull;

import com.fangke.visitor.device.model.AccessEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 新终端报文字段 → 平台字段的映射（协议文档 §4.1 记录推送）。
 *
 * <p>厂商用 {@code openType} 区分开门方式，与我们 {@code access_event.identify_type} 的编码不同，
 * 映射集中在这里，便于真机联调时按实测结果微调（只改这一处）。
 */
public final class OpenApiRecordMapper {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /* 我们内部的 identify_type */
    public static final int IDENTIFY_FACE = 0;
    public static final int IDENTIFY_ID_CARD = 2;
    public static final int IDENTIFY_IC = 3;
    public static final int IDENTIFY_QR = 5;
    public static final int IDENTIFY_PASSWORD = 6;
    public static final int IDENTIFY_MANUAL = 9;

    /** access_event.source：1 设备回调。 */
    public static final int SOURCE_DEVICE = 1;

    private OpenApiRecordMapper() {
    }

    /**
     * 厂商 openType → 平台 identify_type。
     *
     * @param openType 0 陌生人脸 1 白名单人脸 2 身份证 3 IC卡 4 远程开门 5 二维码 6 密码
     *                 7 拨号 8 物业管理处 9 身份证人证库 10 IC 人卡库 11 未知
     */
    public static int identifyType(Integer openType) {
        if (openType == null) {
            return IDENTIFY_FACE;
        }
        return switch (openType) {
            case 0, 1 -> IDENTIFY_FACE;
            case 2, 9 -> IDENTIFY_ID_CARD;
            case 3, 10 -> IDENTIFY_IC;
            case 4, 7, 8 -> IDENTIFY_MANUAL;
            case 5 -> IDENTIFY_QR;
            case 6 -> IDENTIFY_PASSWORD;
            default -> IDENTIFY_FACE;
        };
    }

    public static String identifyText(int identifyType) {
        return switch (identifyType) {
            case IDENTIFY_FACE -> "人脸";
            case IDENTIFY_ID_CARD -> "人证";
            case IDENTIFY_IC -> "IC卡";
            case IDENTIFY_QR -> "二维码";
            case IDENTIFY_PASSWORD -> "密码";
            case IDENTIFY_MANUAL -> "人工开门";
            default -> "其他";
        };
    }

    /** 设备 time（yyyy-MM-dd HH:mm:ss）；解析失败返回 null 由调用方兜底。 */
    public static LocalDateTime eventTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), EVENT_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 身份证号脱敏（设备上报的是明文，落库前必须脱敏，BR-D-002）。 */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.isBlank()) {
            return null;
        }
        String v = idCard.trim();
        if (v.length() < 8) {
            return "****";
        }
        return v.substring(0, 6) + "********" + v.substring(v.length() - 4);
    }

    public static BigDecimal temperature(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 幂等键：优先 tableID，其次 id（设备时间戳串）；都为空时用 设备+时间+证件 拼一个。
     */
    public static String eventUuid(String tableId, String id, String deviceNo,
                                   LocalDateTime time, String idCard) {
        if (tableId != null && !tableId.isBlank()) {
            return "T" + tableId.trim();
        }
        if (id != null && !id.isBlank()) {
            return "D" + id.trim();
        }
        return "X" + (deviceNo == null ? "" : deviceNo) + "-"
                + (time == null ? "0" : time.toString()) + "-"
                + (idCard == null ? "" : idCard.hashCode());
    }

    /** 组装统一通行事件（照片由调用方异步上传）。 */
    public static AccessEvent toEvent(String deviceNo, Long deviceId, Long gateId, Integer inout,
                                      Integer openType, Integer resultStatus, String idCard,
                                      String name, String personNo, LocalDateTime time,
                                      String temperature, String rawJson) {
        int direction = inout == null ? 1 : inout;
        int identify = identifyType(openType);
        int result = resultStatus == null ? 0 : resultStatus;
        return new AccessEvent(
                eventUuid(null, null, deviceNo, time, idCard),
                deviceId,
                deviceNo,
                gateId,
                direction,
                time == null ? LocalDateTime.now() : time,
                identify,
                personNo,
                name,
                maskIdCard(idCard),
                result,
                result == 1 ? null : "设备判定未通过",
                null,
                temperature(temperature),
                false,
                0,
                SOURCE_DEVICE,
                rawJson);
    }
}
