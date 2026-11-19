package com.fangke.visitor.appointment.dto;

/**
 * 隐私告知同意（BR-D-003：必须单独勾选，不得与其他条款合并）。
 *
 * @param consentType 1 个人信息处理告知 2 代邀授权
 */
public record ConsentRequest(
        String name,
        String idCard,
        String phone,
        String company,
        Integer consentType,
        String version) {
}
