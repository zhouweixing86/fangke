package com.fangke.visitor.device.service;

import com.fangke.visitor.device.model.AccessEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用内存事件仓储：模拟唯一键幂等语义与自增主键。
 */
public class InMemoryAccessEventRepository implements AccessEventRepository {

    private final Map<String, Long> idsByUuid = new ConcurrentHashMap<>();
    private final List<AccessEvent> events = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public Optional<Long> insertIfAbsent(AccessEvent event) {
        if (idsByUuid.containsKey(event.eventUuid())) {
            return Optional.empty();
        }
        long id = seq.incrementAndGet();
        idsByUuid.put(event.eventUuid(), id);
        events.add(event);
        return Optional.of(id);
    }

    public List<AccessEvent> events() {
        return events;
    }

    @Override
    public Optional<Long> findPlatformQrEvent(Long gateId, java.time.LocalDateTime eventTime) {
        // 内存实现不做二维码合并，直接返回空（等价于"没有平台记录"）
        return Optional.empty();
    }
}
