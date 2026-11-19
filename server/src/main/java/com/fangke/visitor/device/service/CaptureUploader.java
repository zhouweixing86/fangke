package com.fangke.visitor.device.service;

/**
 * 抓拍照片异步上传。
 *
 * <p>为什么异步：设备回调必须尽快回 {@code ok}（否则设备重推），
 * 上传对象存储是慢操作，不能放在 ack 之前。照片丢了有日志可查，
 * ack 晚了会直接导致设备端排队重试。
 */
public interface CaptureUploader {

    void uploadAsync(Long eventId, String deviceSn, String eventUuid, String faceBase64);

    /** 测试或未配置存储时使用。 */
    static CaptureUploader noop() {
        return (eventId, deviceSn, eventUuid, faceBase64) -> {
        };
    }
}
