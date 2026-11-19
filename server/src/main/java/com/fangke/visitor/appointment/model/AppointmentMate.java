package com.fangke.visitor.appointment.model;

/**
 * 预约同行人（docs/10 §5 appointment_mate）。
 *
 * @param main true 表示主访客本人（预约单必有 1 条），false 为同行人
 */
public record AppointmentMate(
        Long id,
        Long appointmentId,
        String name,
        byte[] idCardEnc,
        String idCardHmac,
        String idCardMask,
        byte[] phoneEnc,
        String phoneMask,
        boolean main,
        String personNo) {
}
