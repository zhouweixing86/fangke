package com.fangke.visitor.employee.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 员工照片校验与压缩（docs/02 P-M-03、BR-E-003~006）。
 *
 * <p>已实现：≤2MB（超出自动压缩）、最小边 400×400、可解码、下发给设备前压到 100~200KB 且不超过 1080×1920。
 * <b>未实现</b>：人脸数量检测（需要人脸算法 SDK），当前只保留错误码 1008 的位置，
 * 接入算法后在 {@link #validate} 里补一行即可。
 */
@Component
public class PhotoValidator {

    public record Info(int width, int height, int sizeBytes) {
    }

    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MIN_SIDE = 400;
    private static final int MAX_WIDTH = 1080;
    private static final int MAX_HEIGHT = 1920;
    /** 下发给设备的压缩目标（BR-E-006：100~200KB）。 */
    private static final int TARGET_MAX_BYTES = 200 * 1024;

    /**
     * 校验 + 规整上传照片：超过 2MB 自动压缩到 2MB 以内（优先降质量，其次缩尺寸）。
     *
     * <p>现场常见的是手机原图（2~4MB、3000×4000），硬拒体验很差，所以这里先压缩再校验；
     * 分辨率只在"太小"时拒绝，太大由设备压缩步骤自动缩放。
     */
    public Prepared prepare(byte[] data) {
        if (data == null || data.length == 0) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "照片内容为空");
        }
        byte[] normalized = data.length > MAX_UPLOAD_BYTES ? shrinkToLimit(data) : data;
        return new Prepared(normalized, validate(normalized));
    }

    /** 规整后的照片字节 + 尺寸信息。 */
    public record Prepared(byte[] data, Info info) {
    }

    public Info validate(byte[] data) {
        if (data == null || data.length == 0) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "照片内容为空");
        }
        if (data.length > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "照片不能超过 2MB（当前 " + (data.length / 1024) + "KB）");
        }
        BufferedImage image = decode(data);
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < MIN_SIDE || height < MIN_SIDE) {
            throw new BusinessException(ErrorCode.IMAGE_RESOLUTION,
                    "照片分辨率需不低于 400×400（当前 " + width + "×" + height + "）");
        }
        // 尺寸过大不再拒绝：下发前 compressForDevice 会自动缩到 1080×1920 以内
        return new Info(width, height, data.length);
    }

    /** 超过 2MB 时逐级降质量、再按比例缩尺寸，直到达标。 */
    byte[] shrinkToLimit(byte[] data) {
        BufferedImage current = decode(data);
        for (int round = 0; round < 12; round++) {
            for (float quality = 0.90f; quality >= 0.50f; quality -= 0.10f) {
                byte[] out = encodeJpeg(current, quality);
                if (out.length <= MAX_UPLOAD_BYTES) {
                    return out;
                }
            }
            if (current.getWidth() <= MIN_SIDE * 2) {
                break;
            }
            current = scaleBy(current, 0.80);
        }
        throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "照片压缩后仍超过 2MB，请换一张更小的照片");
    }

    private BufferedImage scaleBy(BufferedImage source, double factor) {
        int targetWidth = Math.max(1, (int) (source.getWidth() * factor));
        int targetHeight = Math.max(1, (int) (source.getHeight() * factor));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * 压缩到目标体积（优先降质量，其次缩尺寸）。
     */
    public byte[] compressForDevice(byte[] data) {
        BufferedImage image = decode(data);
        BufferedImage scaled = scaleToFit(image, MAX_WIDTH, MAX_HEIGHT);
        float quality = 0.85f;
        byte[] result = encodeJpeg(scaled, quality);
        while (result.length > TARGET_MAX_BYTES && quality > 0.35f) {
            quality -= 0.1f;
            result = encodeJpeg(scaled, quality);
        }
        // 极端情况（噪点多的照片）：继续缩尺寸
        int width = scaled.getWidth();
        while (result.length > TARGET_MAX_BYTES && width > MIN_SIDE) {
            width = (int) (width * 0.85);
            scaled = scaleToFit(scaled, width, (int) (width * 1.3));
            result = encodeJpeg(scaled, 0.7f);
        }
        return result;
    }

    private BufferedImage decode(byte[] data) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                throw new BusinessException(ErrorCode.IMAGE_FACE_INVALID, "无法识别的图片格式，请上传 JPG/PNG");
            }
            return image;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.IMAGE_FACE_INVALID, "照片解码失败：" + e.getMessage());
        }
    }

    private BufferedImage scaleToFit(BufferedImage source, int maxWidth, int maxHeight) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= maxWidth && height <= maxHeight) {
            return source;
        }
        double ratio = Math.min((double) maxWidth / width, (double) maxHeight / height);
        int targetWidth = Math.max(1, (int) (width * ratio));
        int targetHeight = Math.max(1, (int) (height * ratio));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "当前 JRE 不支持 JPEG 编码");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(toRgb(image), null, null), param);
            stream.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "照片压缩失败：" + e.getMessage());
        } finally {
            writer.dispose();
        }
    }

    private BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }
}
