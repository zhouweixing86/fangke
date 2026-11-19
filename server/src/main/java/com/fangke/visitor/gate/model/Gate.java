package com.fangke.visitor.gate.model;

/**
 * 门点（对应 docs/10 §4 gate 表）。
 *
 * <p>厂区有多个出入口，预约授权、设备归属、通行记录、报表都按门点区分。
 * {@link #TYPE_UNASSIGNED} 是系统保留类型，用于轮询式终端首次心跳时的「待分配」占位。
 */
public record Gate(Long id,
                   String gateCode,
                   String gateName,
                   int gateType,
                   int status,
                   int sortNo,
                   String remark) {

    public static final int TYPE_MAIN = 1;
    public static final int TYPE_SIDE = 2;
    public static final int TYPE_GARAGE = 3;
    /** 待分配：设备自动登记的临时门点，业务上不可作为授权门点。 */
    public static final int TYPE_UNASSIGNED = 9;

    public static final String CODE_UNASSIGNED = "UNASSIGNED";

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    public boolean enabled() {
        return status == STATUS_ENABLED;
    }

    /** 待分配门点：不能作为预约授权门点。 */
    public boolean unassigned() {
        return gateType == TYPE_UNASSIGNED || CODE_UNASSIGNED.equalsIgnoreCase(gateCode);
    }

    public String statusText() {
        return enabled() ? "启用" : "停用";
    }

    public String typeText() {
        return typeText(gateType);
    }

    public static String typeText(int gateType) {
        return switch (gateType) {
            case TYPE_MAIN -> "大门";
            case TYPE_SIDE -> "侧门";
            case TYPE_GARAGE -> "车库门";
            case TYPE_UNASSIGNED -> "待分配";
            default -> "其他";
        };
    }
}
