package com.fangke.visitor.admin.repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 报表统计（docs/11 §5.8）。只读聚合查询，口径见 docs/01 报表章节。
 */
public interface AdminReportRepository {

    record Overview(long appointments,
                    long approved,
                    long rejected,
                    long pending,
                    long withdrawn,
                    long checkins,
                    long onsiteNow,
                    long events,
                    long eventPass,
                    long eventFail,
                    Double avgApprovalMinutes) {
    }

    record Daily(LocalDate date,
                 long appointments,
                 long checkins,
                 long eventPass,
                 long eventFail) {
    }

    /** 访客维度统计：按部门或按访客类型。 */
    record VisitorStat(String key, long total, long approved, long arrived) {
    }

    /** 通行维度统计：按设备或按识别方式。 */
    record AccessStat(String key, long total, long pass, long fail) {
    }

    Overview overview(LocalDate from, LocalDate to);

    /** 逐日明细，区间内**每天都有行**（无数据补 0，方便前端画图）。 */
    List<Daily> daily(LocalDate from, LocalDate to);

    List<VisitorStat> visitorByDept(LocalDate from, LocalDate to);

    List<VisitorStat> visitorByType(LocalDate from, LocalDate to);

    List<AccessStat> accessByDevice(LocalDate from, LocalDate to);

    List<AccessStat> accessByIdentifyType(LocalDate from, LocalDate to);
}
