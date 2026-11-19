package com.fangke.visitor.device.callback.dto;

/**
 * 在线比对应答体：{@code status=0} 开门，非 0 不开门。
 *
 * <p>{@code msg} 显示在设备屏幕，{@code audio} 需设备开启自定义语音。
 */
public record CompareResponse(int status, String msg, String audio) {

    /** 放行。 */
    public static CompareResponse pass(String msg) {
        return new CompareResponse(0, msg, msg);
    }

    /** 拒绝：status 取 docs/11 §1.3 的 2~6。 */
    public static CompareResponse reject(int status, String msg) {
        return new CompareResponse(status, msg, msg);
    }

    // ---- status 语义（与 docs/11 §1.3 一致） ----
    public static final int INVALID = 2;
    public static final int EXPIRED = 3;
    public static final int NO_GRANT = 4;
    public static final int OUT_OF_WINDOW = 5;
    public static final int GATE_MISMATCH = 6;

    public static CompareResponse invalid() {
        return reject(INVALID, "二维码无效");
    }

    public static CompareResponse expired() {
        return reject(EXPIRED, "二维码已过期，请刷新");
    }

    public static CompareResponse noGrant() {
        return reject(NO_GRANT, "无通行授权");
    }

    public static CompareResponse outOfWindow() {
        return reject(OUT_OF_WINDOW, "不在授权时间");
    }

    public static CompareResponse gateMismatch() {
        return reject(GATE_MISMATCH, "门点不符");
    }
}
