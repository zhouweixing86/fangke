package com.fangke.visitor.vehicle.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 云车场签名（docs/19 §1）：ASCII 升序 + 末尾 key + MD5 大写；sign/data 不参与。 */
class CloudParkingSignTest {

    @Test
    @DisplayName("固定向量：a=1&b=2 加上 key 后 MD5 大写")
    void signMatchesKnownVector() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("b", "2");
        data.put("a", "1");
        // md5("a=1&b=2&key=key123") = 21489F4A413053A5C8C8B31DEA8439E3
        assertEquals("21489F4A413053A5C8C8B31DEA8439E3", CloudParkingSign.sign(data, "key123"));
    }

    @Test
    @DisplayName("空值/null 不参与签名，sign 与 data 字段被排除")
    void signSkipsEmptyAndReservedFields() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plateNumber", " 京A12345 ");
        data.put("personName", "");
        data.put("mobile", null);
        data.put("sign", "SHOULD_BE_IGNORED");
        data.put("data", new String[]{"x"});
        data.put("park_id", "P01");
        String expected = CloudParkingSign.md5Upper("park_id=P01&plateNumber=京A12345&key=s3cret");
        assertEquals(expected, CloudParkingSign.sign(data, "s3cret"));
    }

    @Test
    @DisplayName("签名缺少参数或密钥直接拒绝")
    void signRejectsMissingInput() {
        assertThrows(IllegalArgumentException.class, () -> CloudParkingSign.sign(Map.of(), "k"));
        assertThrows(IllegalArgumentException.class, () -> CloudParkingSign.sign(Map.of("a", "1"), " "));
    }
}
