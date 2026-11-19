package com.fangke.visitor.qr.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

/**
 * 二维码图片生成（PNG + Base64）。
 *
 * <p>为什么放在后端：小程序与 APP 没有 DOM，前端 canvas 方案要么不可用、要么各端差异大；
 * 后端渲染成 PNG 后，H5/小程序/APK 三端都只是一个 {@code <image src="data:image/png;base64,...">}。
 *
 * <p>服务端无头模式即可（Spring Boot 默认 headless），不依赖字体与显示环境。
 */
@Service
public class QrImageService {

    private static final Logger log = LoggerFactory.getLogger(QrImageService.class);
    /** 码内全是 ASCII，用 M 级纠错足够，且尺寸小、扫码快。 */
    private static final int DEFAULT_SIZE = 280;

    public String pngBase64(String text) {
        return pngBase64(text, DEFAULT_SIZE);
    }

    public String pngBase64(String text, int size) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int px = Math.min(Math.max(size, 120), 800);
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, px, px, Map.of(
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 1));

            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            // 二维码只是展示辅助：生成失败不能阻断签发（前端会退化为只显示文本码）
            log.warn("二维码图片生成失败：{}", e.getMessage());
            return null;
        }
    }
}
