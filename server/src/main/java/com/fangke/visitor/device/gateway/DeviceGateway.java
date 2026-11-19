package com.fangke.visitor.device.gateway;

import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceException;
import com.fangke.visitor.device.model.Device;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 设备指令网关：<b>同一台设备的指令严格串行</b>，跨设备并行，失败按指数退避重试。
 *
 * <p>为什么必须串行：协议 {@code addDeviceWhiteList} 带 {@code totalnum/currentnum}
 * 批内顺序语义，并发下发会打乱编号甚至互相覆盖（docs/10 §4 说明）。
 */
@Component
public class DeviceGateway {

    private static final Logger log = LoggerFactory.getLogger(DeviceGateway.class);

    private final AppProperties props;
    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();

    public DeviceGateway(AppProperties props) {
        this.props = props;
    }

    /**
     * 在设备专属串行队列中执行，带重试。
     *
     * @param device   目标设备
     * @param action   实际动作（一般是 adapter 的方法调用）
     * @param describe 用于日志的动作描述
     */
    public <T> T execute(Device device, String describe, Function<Device, T> action) {
        String key = keyOf(device);
        ExecutorService executor = executors.computeIfAbsent(key, k -> Executors.newSingleThreadExecutor(threadFactory(k)));
        try {
            return executor.submit(() -> runWithRetry(device, describe, action)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "设备指令被中断：" + describe, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DeviceException de) {
                throw de;
            }
            throw new DeviceException(ErrorCode.DEVICE_OFFLINE, "设备指令失败：" + describe, cause);
        }
    }

    private <T> T runWithRetry(Device device, String describe, Function<Device, T> action) {
        int maxRetry = Math.max(1, props.getDevice().getMaxRetry());
        long backoffMs = 200L;
        DeviceException last = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                T result = action.apply(device);
                if (attempt > 1) {
                    log.info("设备指令重试成功 device={} action={} attempt={}", device.getDeviceSn(), describe, attempt);
                }
                return result;
            } catch (DeviceException e) {
                last = e;
                // 密码错误属于配置问题，重试没有意义
                if (e.getCode() == ErrorCode.DEVICE_PASSWORD.code()) {
                    throw e;
                }
                log.warn("设备指令失败 device={} action={} attempt={}/{} code={} msg={}",
                        device.getDeviceSn(), describe, attempt, maxRetry, e.getCode(), e.getMessage());
                if (attempt < maxRetry) {
                    sleep(backoffMs);
                    backoffMs *= 2;
                }
            }
        }
        throw last == null
                ? new DeviceException(ErrorCode.DEVICE_OFFLINE, "设备指令失败：" + describe)
                : last;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String keyOf(Device device) {
        return device.getDeviceSn() != null ? device.getDeviceSn() : String.valueOf(device.getId());
    }

    private ThreadFactory threadFactory(String key) {
        return runnable -> {
            Thread t = new Thread(runnable, "dev-cmd-" + key + "-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    public void shutdown() {
        executors.values().forEach(ExecutorService::shutdownNow);
        executors.clear();
    }
}
