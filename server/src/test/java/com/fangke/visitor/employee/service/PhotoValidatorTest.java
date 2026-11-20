package com.fangke.visitor.employee.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoValidatorTest {

    private final PhotoValidator validator = new PhotoValidator();

    /** 生成纯色/噪点 JPEG。 */
    private byte[] jpeg(int width, int height, boolean noise) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 120, 200));
        g.fillRect(0, 0, width, height);
        if (noise) {
            java.util.Random random = new java.util.Random(42);
            for (int y = 0; y < height; y += 3) {
                for (int x = 0; x < width; x += 3) {
                    image.setRGB(x, y, random.nextInt(0xFFFFFF));
                }
            }
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** 无损 PNG 噪点图：体积远大于 JPEG，用于测 2MB 自动压缩。 */
    private byte[] pngNoise(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("合规照片：500×600 通过校验")
    void validPhoto() throws IOException {
        byte[] data = jpeg(500, 600, false);
        PhotoValidator.Info info = validator.validate(data);

        assertEquals(500, info.width());
        assertEquals(600, info.height());
        assertEquals(data.length, info.sizeBytes());
    }

    @Test
    @DisplayName("分辨率过小被拒（1007）")
    void tooSmall() throws IOException {
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(jpeg(200, 200, false)));
        assertEquals(ErrorCode.IMAGE_RESOLUTION.code(), e.getCode());
    }

    @Test
    @DisplayName("分辨率过大不再拒绝，交给下发前缩放")
    void largeDimensionsAccepted() throws IOException {
        PhotoValidator.Info info = validator.validate(jpeg(2000, 2000, false));
        assertEquals(2000, info.width());

        byte[] compressed = validator.compressForDevice(jpeg(2000, 2000, false));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compressed));
        assertTrue(decoded.getWidth() <= 1080 && decoded.getHeight() <= 1920);
    }

    @Test
    @DisplayName("超过 2MB 自动压缩到 2MB 以内")
    void oversizeAutoShrunk() throws IOException {
        // 无损 PNG 噪点图必然超过 2MB
        byte[] data = pngNoise(1000, 1000);
        assertTrue(data.length > 2 * 1024 * 1024, "测试素材应大于 2MB，实际 " + data.length);

        PhotoValidator.Prepared prepared = validator.prepare(data);
        assertTrue(prepared.data().length <= 2 * 1024 * 1024,
                "压缩后应 ≤2MB，实际 " + prepared.data().length);
        assertTrue(ImageIO.read(new ByteArrayInputStream(prepared.data())) != null);
        assertEquals(prepared.data().length, prepared.info().sizeBytes());
    }

    @Test
    @DisplayName("2MB 以内的照片原样保留，不重复压缩")
    void smallPhotoKeptAsIs() throws IOException {
        byte[] data = jpeg(500, 600, false);
        PhotoValidator.Prepared prepared = validator.prepare(data);
        assertTrue(prepared.data() == data, "未超限时不应重新编码");
    }

    @Test
    @DisplayName("无法解码的内容被拒（1008）")
    void notAnImage() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate("hello".getBytes()));
        assertEquals(ErrorCode.IMAGE_FACE_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("压缩后体积 ≤200KB，且仍可解码")
    void compressesForDevice() throws IOException {
        byte[] source = jpeg(900, 1300, true);
        byte[] compressed = validator.compressForDevice(source);

        assertTrue(compressed.length <= 200 * 1024, "压缩后应 ≤200KB，实际 " + compressed.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compressed));
        assertTrue(decoded != null);
        assertTrue(decoded.getWidth() <= 1080 && decoded.getHeight() <= 1920);
    }
}
