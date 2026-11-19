package com.fangke.visitor.device.pull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新终端签名规则（docs/14 §3）。
 *
 * <p>文档里的示例串存在笔误（{@code driverno}、{@code ver =v1.0}），所以这里用
 * 自己构造的字符串锁定"排除 data/sign + ASCII 升序 + 末尾 &key= + MD5 大写"这条规则，
 * 真机联调时如果对不上，直接改这里一处即可。
 */
class OpenApiSignTest {

    private static final String KEY = "asdfgthew634weg";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("签名：排除 data 与 sign，按字段名升序，末尾拼 &key=")
    void signRule() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ver", "v1.0");
        params.put("deviceno", "2002030984505");
        params.put("timestamp", "20211119101010123");
        params.put("action", "heartBeat");
        params.put("data", "{\"x\":1}");
        params.put("sign", "IGNORED");

        String expectedRaw = "action=heartBeat&deviceno=2002030984505"
                + "&timestamp=20211119101010123&ver=v1.0&key=" + KEY;
        assertEquals(OpenApiSign.md5Upper(expectedRaw), OpenApiSign.sign(params, KEY));
    }

    @Test
    @DisplayName("签名：字段顺序不影响结果，值为空/null 的字段不参与")
    void signIsStable() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("action", "heartBeat");
        a.put("deviceno", "SN1");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("deviceno", "SN1");
        b.put("action", "heartBeat");
        assertEquals(OpenApiSign.sign(a, KEY), OpenApiSign.sign(b, KEY));

        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put("action", "heartBeat");
        withNull.put("deviceno", "SN1");
        withNull.put("mac", null);
        assertEquals(OpenApiSign.sign(a, KEY), OpenApiSign.sign(withNull, KEY));
    }

    @Test
    @DisplayName("签名固定值：同样的输入永远得到同一个大写 MD5（防篡改回归）")
    void signMatchesKnownValue() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("action", "heartBeat");
        params.put("deviceno", "SN1");
        String raw = "action=heartBeat&deviceno=SN1&key=" + KEY;
        String expected = OpenApiSign.md5Upper(raw);
        assertEquals(expected, OpenApiSign.sign(params, KEY));
        assertTrue(expected.matches("[0-9A-F]{32}"), "必须是大写 32 位");
    }

    @Test
    @DisplayName("校验：报文里 data 变化不影响签名，sign 缺失判失败")
    void verifyIgnoresData() throws Exception {
        String raw = "{\"ver\":\"v1.0\",\"deviceno\":\"SN1\",\"timestamp\":\"20211119101010123\","
                + "\"action\":\"heartBeat\",\"data\":{\"a\":1}}";
        JsonNode body = mapper.readTree(raw);
        String sign = OpenApiSign.expectedSign(body, KEY);

        JsonNode withSign = mapper.readTree(raw.substring(0, raw.length() - 1)
                + ",\"sign\":\"" + sign + "\"}");
        assertTrue(OpenApiSign.verify(withSign, KEY));

        JsonNode tamperedData = mapper.readTree(raw.replace("{\"a\":1}", "{\"a\":2}").substring(0,
                raw.replace("{\"a\":1}", "{\"a\":2}").length() - 1) + ",\"sign\":\"" + sign + "\"}");
        assertTrue(OpenApiSign.verify(tamperedData, KEY), "data 不参与签名");

        JsonNode tamperedDevice = mapper.readTree(raw.replace("SN1", "SN2").substring(0,
                raw.replace("SN1", "SN2").length() - 1) + ",\"sign\":\"" + sign + "\"}");
        assertFalse(OpenApiSign.verify(tamperedDevice, KEY), "顶层字段被改必须失败");

        assertFalse(OpenApiSign.verify(body, KEY), "缺 sign 判失败");
    }

    @Test
    @DisplayName("参与签名的字段列表：不含 data/sign，且已排序")
    void signedFields() throws Exception {
        JsonNode body = mapper.readTree("{\"ver\":\"v1.0\",\"deviceno\":\"SN1\",\"action\":\"heartBeat\","
                + "\"data\":{},\"sign\":\"X\"}");
        assertEquals(List.of("action", "deviceno", "ver"), OpenApiSign.signedFields(body));
    }
}
