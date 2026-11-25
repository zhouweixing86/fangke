package com.fangke.visitor.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 设备参数的「平台期望值」（docs/14 §5、docs/17 §11.2）。
 *
 * <p>轮询式设备不能被平台直连，改参数的唯一通道是：<b>平台存期望值 → 设备心跳取 action
 * {@code syncDeviceParameter} → 平台在响应里带上期望值</b>。
 * 这里只放行白名单键，避免把设备 IP / 平台地址这类「改了会失联」的参数开放出去。
 */
public final class DeviceParamOverrides {

    /** 允许平台远程写入的设备参数。 */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "videoScanQRcode", "QROpenEnable", "PasswordOpenEnable", "thirdOpenDoorLocal");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeviceParamOverrides() {
    }

    /** 解析成 {key: 0/1}；非法 JSON、未知键、非 0/1 值一律忽略（历史脏数据不阻塞下发）。 */
    public static Map<String, Integer> parse(String json) {
        Map<String, Integer> view = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return view;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return view;
            }
            node.fields().forEachRemaining(entry -> {
                if (!ALLOWED_KEYS.contains(entry.getKey())) {
                    return;
                }
                Integer flag = flagOf(entry.getValue());
                if (flag != null) {
                    view.put(entry.getKey(), flag);
                }
            });
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return view;
    }

    /**
     * 把改动合并进期望值，返回新的 JSON 字符串。
     *
     * @param json    现有期望值
     * @param changes 形如 {@code {videoScanQRcode: true}}
     * @throws BusinessException 出现白名单外的键，或值无法识别成 0/1
     */
    public static String merge(String json, Map<String, ?> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "没有要修改的参数");
        }
        Map<String, Integer> merged = parse(json);
        for (Map.Entry<String, ?> entry : changes.entrySet()) {
            String key = entry.getKey();
            if (!ALLOWED_KEYS.contains(key)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "不允许远程修改设备参数 " + key + "（请在设备本地后台操作）");
            }
            Integer flag = flagOf(entry.getValue());
            if (flag == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, key + " 只支持 0/1 或 true/false");
            }
            merged.put(key, flag);
        }
        ObjectNode root = MAPPER.createObjectNode();
        merged.forEach(root::put);
        return root.toString();
    }

    /** 把期望值合并进 {@code syncDeviceParameter} 的响应体（期望值优先）。 */
    public static void applyTo(String json, Map<String, Object> target) {
        if (target == null) {
            return;
        }
        parse(json).forEach(target::put);
    }

    private static Integer flagOf(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (raw instanceof Number n) {
            return n.intValue() == 0 ? 0 : 1;
        }
        String text = String.valueOf(raw).trim();
        if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
            return 1;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
            return 0;
        }
        return null;
    }

    private static Integer flagOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1 : 0;
        }
        if (node.isNumber()) {
            return node.intValue() == 0 ? 0 : 1;
        }
        return flagOf(node.asText());
    }
}
