package com.fangke.visitor.notify.model;

/**
 * 通知模板（站内/短信共用文案）。
 *
 * <p>编码统一大写下划线，落库后可按 code 统计与替换；短信通道接入后
 * 只需在网关侧维护同 code 的模板，不改业务代码。
 */
public final class NotifyTemplates {

    /** 员工：有新的访客待审批。 */
    public static final String APPROVAL_TODO = "APPROVAL_TODO";
    /** 访客：审批通过。 */
    public static final String APPROVAL_PASSED = "APPROVAL_PASSED";
    /** 访客：审批驳回。 */
    public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
    /** 员工：审批催办（手动或定时）。 */
    public static final String APPROVAL_REMIND = "APPROVAL_REMIND";
    /** 访客：已过期（未按期到访）。 */
    public static final String APPOINTMENT_EXPIRED = "APPOINTMENT_EXPIRED";
    /** 员工：访客已到场。 */
    public static final String VISIT_CHECKIN = "VISIT_CHECKIN";
    /** 后台账号：审批抄送（只读知会）。 */
    public static final String APPROVAL_CC = "APPROVAL_CC";

    public record Content(String title, String content) {
    }

    private NotifyTemplates() {
    }

    public static Content approvalTodo(String visitorName, String appointmentNo,
                                       String timeRange, String purpose) {
        return new Content("新的访客审批",
                String.format("%s 申请 %s 到访（%s），事由：%s。请及时审批。",
                        safe(visitorName), timeRange, appointmentNo, safe(purpose)));
    }

    /** 抄送：流程走到某节点（或提交时），只通知不阻断。 */
    public static Content approvalCc(String visitorName, String appointmentNo,
                                     String timeRange, String stage) {
        return new Content("访客审批抄送",
                String.format("%s 的来访（%s，%s）%s，抄送知会。",
                        safe(visitorName), appointmentNo, timeRange, stage));
    }

    public static Content approvalPassed(String visitorName, String timeRange) {
        return new Content("预约已通过",
                String.format("%s %s 的来访已通过审批，可在「我的预约」出示通行码或到场刷证。",
                        safe(visitorName), timeRange));
    }

    public static Content approvalRejected(String visitorName, String timeRange, String reason) {
        return new Content("预约被驳回",
                String.format("%s %s 的来访被驳回%s", safe(visitorName), timeRange,
                        reason == null || reason.isBlank() ? "。" : "，原因：" + reason));
    }

    public static Content approvalRemind(String visitorName, String appointmentNo, String timeRange) {
        return new Content("审批催办",
                String.format("%s 的预约（%s，%s）仍在等待你审批，请尽快处理。",
                        safe(visitorName), appointmentNo, timeRange));
    }

    public static Content appointmentExpired(String timeRange) {
        return new Content("预约已过期",
                String.format("你 %s 的来访预约已过期未到访，如需来访请重新提交预约。", timeRange));
    }

    public static Content visitCheckin(String visitorName, String timeRange) {
        return new Content("访客已到场",
                String.format("%s 已到场（预约 %s），请做好接待。", safe(visitorName), timeRange));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "访客" : value;
    }
}
