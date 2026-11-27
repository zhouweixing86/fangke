package com.fangke.visitor.appointment.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 提交预约请求（docs/11 §2.2）。
 *
 * <p>同行人（C-111 口径）：只登记姓名 + 手机号，统一「随行一次放行」，跟随主访客一起进出，
 * 不单独下发名单、也不单独刷证。二维码对所有预约默认开通，请求里不再携带。
 *
 * @param gateId    兼容旧调用：单个门点
 * @param gateScope 1 指定门点 / 2 全部门点（C-64）；为 2 时忽略 gateId/gateIds
 * @param gateIds   指定的多个门点
 * @param company   来访公司
 * @param position  来访人职务
 * @param period    时段：1 全天 / 2 上午 / 3 下午；为空时按 startTime/endTime 原样使用
 * @param groupCount 团体总人数（含负责人），可选；同行人数以 mates 为准
 */
public record AppointmentCreateRequest(
        String visitPhone,
        String department,
        String visitorType,
        String purpose,
        String plateNo,
        LocalDate startDate,
        LocalTime startTime,
        LocalTime endTime,
        VisitorInfo visitor,
        List<MateInfo> mates,
        String consentVersion,
        Long gateId,
        Integer gateScope,
        List<Long> gateIds,
        String company,
        String position,
        Integer period,
        Integer groupCount,
        String wxOpenid,
        /** 来访区域（visit_dict AREA，C-75）；不传表示未指定。*/
        Long areaId) {

    public record VisitorInfo(String name, String idCard, String phone, String company, String position) {
    }

    /** 同行人：姓名必填，手机号选填，身份证不再需要（随主访客一起进）。 */
    public record MateInfo(String name, String phone, String idCard) {
    }
}
