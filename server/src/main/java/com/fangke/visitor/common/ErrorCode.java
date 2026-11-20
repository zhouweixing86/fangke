package com.fangke.visitor.common;

/**
 * 错误码全表，与 docs/11-接口详细契约.md §0.7 一一对应。
 *
 * <pre>
 * 1xxx 参数 / 2xxx 鉴权 / 3xxx 业务 / 4xxx 设备通道 / 5xxx 系统
 * </pre>
 */
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // 1xxx 参数
    PARAM_MISSING(1001, "参数缺失"),
    PARAM_INVALID(1002, "参数格式错误"),
    PARAM_TOO_LONG(1003, "字段超长"),
    ID_CARD_INVALID(1004, "身份证号校验位错误"),
    PHONE_INVALID(1005, "手机号格式错误"),
    FILE_TOO_LARGE(1006, "文件过大"),
    IMAGE_RESOLUTION(1007, "图片分辨率不达标"),
    IMAGE_FACE_INVALID(1008, "图片未检测到人脸或多张人脸"),
    NOT_FOUND(1009, "接口不存在"),

    // 2xxx 鉴权
    UNAUTHORIZED(2001, "未登录或 token 失效"),
    FORBIDDEN(2002, "无权限"),
    ACCOUNT_DISABLED(2003, "账号被停用"),

    // 3xxx 业务
    SLOT_EXPIRED(3001, "预约时段已过期"),
    APPOINTMENT_DUPLICATED(3002, "该访客已有进行中的预约"),
    BLACKLISTED(3003, "命中黑名单"),
    STATUS_NOT_ALLOWED(3004, "预约单状态不允许此操作"),
    TASK_ALREADY_HANDLED(3005, "审批任务已被处理"),
    NOT_TASK_OWNER(3006, "不是该任务的审批人"),
    NO_L2_CANDIDATE(3007, "候选中无可用二级审批人"),
    QR_EXPIRED(3008, "二维码已过期"),
    QR_INVALID(3009, "二维码无效"),
    NOT_IN_AUTHORIZED_WINDOW(3010, "不在授权时间"),
    GRANT_NOT_READY(3011, "授权尚未下发完成"),
    INVITE_LIMIT_EXCEEDED(3012, "超出代邀有效期上限"),
    MATE_ID_CARD_REQUIRED(3013, "同行人身份证缺失"),
    EMPLOYEE_NO_FACE(3014, "员工无照片，无法下发"),
    VISITOR_NOT_IN_SITE(3015, "该人员当前不在访"),

    // 4xxx 设备通道
    DEVICE_OFFLINE(4001, "设备离线或连接超时"),
    DEVICE_PASSWORD(4002, "设备密码错误"),
    DEVICE_PARAM_ERROR(4003, "设备参数错误"),
    DEVICE_QUEUE_BACKLOG(4004, "设备指令队列积压"),
    DEVICE_LIST_FULL(4005, "设备名单已满"),
    DEVICE_UNSUPPORTED(4006, "当前设备不支持该能力"),

    // 5xxx 系统
    INTERNAL_ERROR(5001, "系统内部错误"),
    DB_ERROR(5002, "数据库错误"),
    STORAGE_UNAVAILABLE(5003, "对象存储不可用"),
    THIRD_PARTY_ERROR(5004, "第三方接口失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
