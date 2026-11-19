package com.fangke.visitor.dispatch.model;

import java.time.LocalDateTime;

/**
 * 下发批次（docs/10 §7 sync_batch）。
 */
public record SyncBatch(
        Long id,
        String batchNo,
        int triggerType,
        Long gateId,
        int totalCount,
        int successCount,
        int failCount,
        int status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    /** 1审批通过 2定时对账 3手动重下 4首次全量 5清理。 */
    public static final int TRIGGER_APPROVAL = 1;
    public static final int TRIGGER_RECONCILE = 2;
    public static final int TRIGGER_MANUAL = 3;
    public static final int TRIGGER_FULL = 4;
    public static final int TRIGGER_CLEANUP = 5;

    /** 0进行中 1成功 2部分失败 3全失败。 */
    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_PARTIAL = 2;
    public static final int STATUS_FAILED = 3;
}
