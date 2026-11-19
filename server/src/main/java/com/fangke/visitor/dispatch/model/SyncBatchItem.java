package com.fangke.visitor.dispatch.model;

import java.time.LocalDateTime;

/**
 * 下发批次明细（docs/10 §7 sync_batch_item）：逐人逐设备的执行结果。
 */
public record SyncBatchItem(
        Long id,
        Long batchId,
        Long deviceId,
        String personNo,
        int action,
        int status,
        String errorCode,
        String errorMsg,
        LocalDateTime ackedAt) {

    public static final int ACTION_UPSERT = 1;
    public static final int ACTION_REMOVE = 2;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
}
