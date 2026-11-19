package com.fangke.visitor.security;

import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.security.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoServiceTest {

    private CryptoService crypto;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCrypto().setAesKey("unit-test-aes");
        props.getCrypto().setHmacKey("unit-test-hmac");
        crypto = new CryptoService(props);
    }

    @Test
    @DisplayName("加解密可逆，且密文不含明文")
    void roundTrip() {
        String plain = "110101199003074477";
        byte[] enc = crypto.encrypt(plain);

        assertEquals(plain, crypto.decrypt(enc));
        String asText = new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains(plain), "密文里不能出现明文");
    }

    @Test
    @DisplayName("同一明文两次加密结果不同（随机 IV）")
    void randomIv() {
        byte[] a = crypto.encrypt("13800138000");
        byte[] b = crypto.encrypt("13800138000");

        assertFalse(Arrays.equals(a, b));
        assertEquals("13800138000", crypto.decrypt(a));
        assertEquals("13800138000", crypto.decrypt(b));
    }

    @Test
    @DisplayName("HMAC 稳定且与明文不同，可用于唯一索引")
    void hmacStable() {
        String h1 = crypto.hmac("110101199003074477");
        String h2 = crypto.hmac("110101199003074477");
        String h3 = crypto.hmac("110101199003074478");

        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
        assertEquals(64, h1.length(), "SHA256 十六进制应为 64 位");
    }

    @Test
    @DisplayName("脱敏规则：身份证前 6 后 4、手机号前 3 后 4")
    void masks() {
        assertEquals("110101********4477", CryptoService.maskIdCard("110101199003074477"));
        assertEquals("138****8000", CryptoService.maskPhone("13800138000"));
        assertNull(CryptoService.maskIdCard(null));
        assertNull(CryptoService.maskPhone("  "));
    }

    @Test
    @DisplayName("空值与短值不抛异常")
    void edgeCases() {
        assertNull(crypto.encrypt(null));
        assertNull(crypto.decrypt(null));
        assertTrue(crypto.decrypt(new byte[0]) == null);
    }
}
