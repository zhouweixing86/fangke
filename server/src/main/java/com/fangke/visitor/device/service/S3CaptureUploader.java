package com.fangke.visitor.device.service;

import com.fangke.visitor.storage.StorageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 抓拍照片落 MinIO/S3，并写 {@code access_event_media}（docs/10 §8）。
 *
 * <p>线程池有界：宁可丢照片，也不能因为存储慢把回调线程占满——门口不能堵。
 */
@Component
public class S3CaptureUploader implements CaptureUploader {

    private static final Logger log = LoggerFactory.getLogger(S3CaptureUploader.class);
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    /** 抓拍保留 30 天（BR-D-006），到期由 JOB-06 清理。 */
    private static final int RETAIN_DAYS = 30;

    private final StorageService storage;
    private final JdbcTemplate jdbc;
    private final ExecutorService executor;
    private final AtomicInteger seq = new AtomicInteger();

    public S3CaptureUploader(StorageService storage, JdbcTemplate jdbc) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.executor = new ThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "capture-upload-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    @Override
    public void uploadAsync(Long eventId, String deviceSn, String eventUuid, String faceBase64) {
        if (faceBase64 == null || faceBase64.isBlank() || eventId == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        String key = StorageService.captureKey(deviceSn, DATE_PATH.format(today), eventUuid);
        executor.submit(() -> {
            try {
                String stored = storage.putBase64(key, faceBase64, "image/jpeg");
                if (stored == null) {
                    return;
                }
                jdbc.update("INSERT INTO access_event_media (event_id, media_type, oss_key, expire_at) "
                                + "VALUES (?, 1, ?, ?)",
                        eventId, stored, LocalDateTime.now().plusDays(RETAIN_DAYS));
            } catch (Exception e) {
                // 照片丢失只影响取证，不能影响通行与记录
                log.warn("抓拍上传失败 eventId={} key={}：{}", eventId, key, e.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
