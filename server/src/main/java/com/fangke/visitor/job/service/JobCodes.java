package com.fangke.visitor.job.service;

/**
 * 定时任务编码（与 docs/02 第 6 章 JOB 清单对应）。
 */
public final class JobCodes {

    /** 审批超时自动催办。 */
    public static final String APPROVAL_REMIND = "APPROVAL_REMIND";
    /** 到期未到访的预约置为已过期。 */
    public static final String APPOINTMENT_EXPIRE = "APPOINTMENT_EXPIRE";
    /** 在访超时自动签离（异常离场）。 */
    public static final String VISIT_AUTO_CHECKOUT = "VISIT_AUTO_CHECKOUT";
    /** 失败短信重试。 */
    public static final String NOTIFICATION_RETRY = "NOTIFICATION_RETRY";
    /** 数据保留期清理（心跳/已读通知/任务日志）。 */
    public static final String DATA_RETENTION = "DATA_RETENTION";
    /** 过期授权清理（原有任务，纳入统一日志）。 */
    public static final String GRANT_CLEANUP = "GRANT_CLEANUP";
    /** 二维码票据清理（原有任务，纳入统一日志）。 */
    public static final String QR_CLEANUP = "QR_CLEANUP";

    private JobCodes() {
    }
}
