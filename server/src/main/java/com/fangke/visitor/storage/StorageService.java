package com.fangke.visitor.storage;

import java.time.Duration;

/**
 * 对象存储抽象（S3 兼容：MinIO / 阿里云 OSS / 腾讯云 COS）。
 *
 * <p>两类用法：
 * <ul>
 *   <li><b>直传</b>：{@link #presignPut} 给前端，浏览器/小程序直接 PUT，不经应用服务器；</li>
 *   <li><b>服务端上传</b>：{@link #putBase64} 用于设备回调里的抓拍（设备只会把 base64 发给我们）。</li>
 * </ul>
 * 业务代码只依赖本接口，换存储只改配置（docs/10 §13）。
 */
public interface StorageService {

    /** 服务端写入对象，返回 key。 */
    String putObject(String key, byte[] data, String contentType);

    /** 服务端写入 base64 对象（设备抓拍），返回 key。 */
    String putBase64(String key, String base64, String contentType);

    /** 读取对象内容（照片校验/压缩需要）。 */
    byte[] getObject(String key);

    /** 生成前端直传用的预签名 PUT URL。 */
    String presignPut(String key, String contentType, Duration ttl);

    /** 生成短时只读预览 URL（私有桶必须走预签名）。 */
    String presignGet(String key, Duration ttl);

    /** 校验对象是否存在（照片回调二次校验用）。 */
    boolean exists(String key);

    /** OSS 是否已完成配置；未配置时调用方应给出明确提示。 */
    boolean configured();

    /** 按日期分目录的抓拍 key 规则（docs/10 §13.1）。 */
    static String captureKey(String deviceSn, String datePath, String eventUuid) {
        return "event/" + datePath + "/" + deviceSn + "/" + eventUuid + "-capture.jpg";
    }

    /** 员工照片 key 规则。 */
    static String employeeFaceKey(Long employeeId, String datePath, String sha16, boolean standard) {
        String prefix = standard ? "emp-face-std" : "emp-face";
        return prefix + "/" + employeeId + "/" + datePath + "/" + sha16 + ".jpg";
    }
}
