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

    /** 无损 PNG 噪点图：体积远大于 JPEG，用于测 500KB 上限。 */
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
    @DisplayName("分辨率过大被拒（1007）")
    void tooLargeDimensions() throws IOException {
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(jpeg(2000, 2000, false)));
        assertEquals(ErrorCode.IMAGE_RESOLUTION.code(), e.getCode());
    }

    @Test
    @DisplayName("文件超过 500KB 被拒（1006）")
    void tooLargeFile() throws IOException {
        // 无损 PNG 噪点图必然超过 500KB
        byte[] data = pngNoise(900, 900);
        assertTrue(data.length > 500 * 1024, "测试素材应大于 500KB，实际 " + data.length);

        BusinessException e = assertThrows(BusinessException.class, () -> validator.validate(data));
        assertEquals(ErrorCode.FILE_TOO_LARGE.code(), e.getCode());
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
