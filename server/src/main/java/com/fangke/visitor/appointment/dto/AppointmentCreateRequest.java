package com.fangke.visitor.appointment.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 提交预约请求（docs/11 §2.2）。
 *
 * @param mateMode  0 随行 / 1 各自刷证；为 1 时同行人必须带身份证（BR-P-023）
 * @param gateId    兼容旧调用：单个门点
 * @param gateScope 1 指定门点 / 2 全部门点（C-64）；为 2 时忽略 gateId/gateIds
 * @param gateIds   指定的多个门点
 * @param company   来访公司（团体预约时为负责人公司）
 * @param position  来访人职务
 * @param period    时段：1 全天 / 2 上午 / 3 下午；为空时按 startTime/endTime 原样使用
 * @param groupCount 团体预约总人数（含负责人），仅团体预约传
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
        Integer mateMode,
        String consentVersion,
        Long gateId,
        Integer gateScope,
        List<Long> gateIds,
        String company,
        String position,
        Integer period,
        Integer groupCount,
        String wxOpenid,
        /** 访客是否申请二维码开门（null/false 不申请）。需审批人同意后访客端才能取码。 */
        Boolean qrRequested,
        /** 来访区域（visit_dict AREA，C-75）；不传表示未指定（兼容旧版小程序）。*/
        Long areaId) {

    public record VisitorInfo(String name, String idCard, String phone, String company, String position) {
        /** 兼容只有 4 个参数的旧调用。 */
        public VisitorInfo(String name, String idCard, String phone, String company) {
            this(name, idCard, phone, company, null);
        }
    }
    public record MateInfo(String name, String phone, String idCard) {
    }

    /** 兼容不传「申请二维码开门」的旧调用。 */
    public AppointmentCreateRequest(String visitPhone, String department, String visitorType,
                                    String purpose, String plateNo, LocalDate startDate,
                                    LocalTime startTime, LocalTime endTime, VisitorInfo visitor,
                                    List<MateInfo> mates, Integer mateMode, String consentVersion,
                                    Long gateId, Integer gateScope, List<Long> gateIds,
                                    String company, String position, Integer period,
                                    Integer groupCount, String wxOpenid) {
        this(visitPhone, department, visitorType, purpose, plateNo, startDate, startTime, endTime,
                visitor, mates, mateMode, consentVersion, gateId, gateScope, gateIds,
                company, position, period, groupCount, wxOpenid, null, null);
    }
}
