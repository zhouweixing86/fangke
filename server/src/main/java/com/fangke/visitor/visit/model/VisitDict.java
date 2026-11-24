package com.fangke.visitor.visit.model;

/**
 * 基础字典（V26）：事由 / 区域 / 岗位，一套表用 {@code dictType} 区分。
 *
 * <p>对齐参考站「事由信息 / 区域信息 / 岗位管理」三个菜单；访客登记时下拉选用。
 */
public record VisitDict(Long id, String dictType, String name, int sort, int status, String remark) {

    public static final String TYPE_CAUSE = "CAUSE";
    public static final String TYPE_AREA = "AREA";
    public static final String TYPE_POST = "POST";

    public static boolean validType(String type) {
        return TYPE_CAUSE.equals(type) || TYPE_AREA.equals(type) || TYPE_POST.equals(type);
    }
}
