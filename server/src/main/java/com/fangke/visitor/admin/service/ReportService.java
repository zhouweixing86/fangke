package com.fangke.visitor.admin.service;

import com.fangke.visitor.admin.repository.AdminReportRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 报表统计：区间校验 + 默认近 30 天。
 */
@Service
public class ReportService {

    /** 单次最多查一年，避免误查全表把库拖住。 */
    private static final int MAX_DAYS = 366;

    private final AdminReportRepository repository;

    public ReportService(AdminReportRepository repository) {
        this.repository = repository;
    }

    public record Range(LocalDate from, LocalDate to) {
    }

    public Range resolveRange(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "开始日期不能晚于结束日期");
        }
        if (start.plusDays(MAX_DAYS).isBefore(end)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "查询区间不能超过 " + MAX_DAYS + " 天");
        }
        return new Range(start, end);
    }

    public AdminReportRepository.Overview overview(LocalDate from, LocalDate to) {
        Range range = resolveRange(from, to);
        return repository.overview(range.from(), range.to());
    }

    public List<AdminReportRepository.Daily> daily(LocalDate from, LocalDate to) {
        Range range = resolveRange(from, to);
        return repository.daily(range.from(), range.to());
    }

    public record VisitorReport(List<AdminReportRepository.VisitorStat> byDept,
                                List<AdminReportRepository.VisitorStat> byType) {
    }

    public record AccessReport(List<AdminReportRepository.AccessStat> byDevice,
                               List<AdminReportRepository.AccessStat> byIdentifyType) {
    }

    public VisitorReport visitorReport(LocalDate from, LocalDate to) {
        Range range = resolveRange(from, to);
        return new VisitorReport(repository.visitorByDept(range.from(), range.to()),
                repository.visitorByType(range.from(), range.to()));
    }

    public AccessReport accessReport(LocalDate from, LocalDate to) {
        Range range = resolveRange(from, to);
        return new AccessReport(repository.accessByDevice(range.from(), range.to()),
                repository.accessByIdentifyType(range.from(), range.to()));
    }
}
