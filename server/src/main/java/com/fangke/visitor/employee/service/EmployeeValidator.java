package com.fangke.visitor.employee.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;

/**
 * 员工字段基础校验（PRD：工号唯一、手机号格式、证件号校验位）。
 */
public final class EmployeeValidator {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private EmployeeValidator() {
    }

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, field + "不能为空");
        }
        return value.trim();
    }

    public static String validateEmpNo(String empNo) {
        String v = requireText(empNo, "工号");
        if (!v.matches("[A-Za-z0-9_-]{1,16}")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "工号只能是 1~16 位字母、数字、下划线或短横线");
        }
        return v;
    }

    public static String validatePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String v = phone.trim();
        if (!v.matches("1[3-9]\\d{9}")) {
            throw new BusinessException(ErrorCode.PHONE_INVALID, "手机号格式不正确：" + v);
        }
        return v;
    }

    public static String validateIdCard(String idCard) {
        if (idCard == null || idCard.isBlank()) {
            return null;
        }
        String v = idCard.trim().toUpperCase();
        if (!v.matches("\\d{17}[0-9X]")) {
            throw new BusinessException(ErrorCode.ID_CARD_INVALID, "身份证号需为 18 位");
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (v.charAt(i) - '0') * WEIGHTS[i];
        }
        if (CHECK_CODES[sum % 11] != v.charAt(17)) {
            throw new BusinessException(ErrorCode.ID_CARD_INVALID, "身份证号校验位错误：" + v);
        }
        return v;
    }
}
