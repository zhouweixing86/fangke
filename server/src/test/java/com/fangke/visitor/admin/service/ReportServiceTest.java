package com.fangke.visitor.admin.service;

import com.fangke.visitor.admin.repository.AdminReportRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表区间校验与默认值。
 */
class ReportServiceTest {

    private final FakeRepo repo = new FakeRepo();
    private final ReportService service = new ReportService(repo);

    @Test
    @DisplayName("不传区间：默认近 30 天（含今天）")
    void defaultRangeIsLast30Days() {
        ReportService.Range range = service.resolveRange(null, null);

        assertEquals(LocalDate.now(), range.to());
        assertEquals(LocalDate.now().minusDays(29), range.from());
    }

    @Test
    @DisplayName("只传结束日期：从结束日期往前 30 天")
    void fromDerivedFromTo() {
        LocalDate to = LocalDate.of(2026, 11, 16);
        ReportService.Range range = service.resolveRange(null, to);

        assertEquals(to.minusDays(29), range.from());
        assertEquals(to, range.to());
    }

    @Test
    @DisplayName("开始晚于结束：拒绝")
    void rejectsReversedRange() {
        LocalDate from = LocalDate.of(2026, 11, 16);
        LocalDate to = LocalDate.of(2026, 11, 1);

        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.resolveRange(from, to)).getCode());
    }

    @Test
    @DisplayName("区间超过 366 天：拒绝，避免全表扫描")
    void rejectsTooLongRange() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 1);

        assertEquals(ErrorCode.PARAM_INVALID.code(), assertThrows(BusinessException.class,
                () -> service.resolveRange(from, to)).getCode());
    }

    @Test
    @DisplayName("正好 366 天：允许")
    void allowsOneYear() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = from.plusDays(366);

        assertEquals(to, service.resolveRange(from, to).to());
    }

    @Test
    @DisplayName("概览与逐日均透传给仓储，且带上解析后的区间")
    void delegatesToRepository() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 16);

        service.overview(from, to);
        service.daily(from, to);

        assertEquals(from, repo.lastOverviewRange[0]);
        assertEquals(to, repo.lastOverviewRange[1]);
        assertEquals(from, repo.lastDailyRange[0]);
        assertEquals(to, repo.lastDailyRange[1]);
        assertTrue(repo.dailyRows.isEmpty() || repo.dailyRows.size() == 1);
    }

    static class FakeRepo implements AdminReportRepository {

        final LocalDate[] lastOverviewRange = new LocalDate[2];
        final LocalDate[] lastDailyRange = new LocalDate[2];
        final List<Daily> dailyRows = new ArrayList<>();

        @Override
        public Overview overview(LocalDate from, LocalDate to) {
            lastOverviewRange[0] = from;
            lastOverviewRange[1] = to;
            return new Overview(10, 6, 2, 1, 1, 5, 1, 20, 18, 2, 12.5);
        }

        @Override
        public List<VisitorStat> visitorByDept(LocalDate from, LocalDate to) {
            return List.of();
        }

        @Override
        public List<VisitorStat> visitorByType(LocalDate from, LocalDate to) {
            return List.of();
        }

        @Override
        public List<AccessStat> accessByDevice(LocalDate from, LocalDate to) {
            return List.of();
        }

        @Override
        public List<AccessStat> accessByIdentifyType(LocalDate from, LocalDate to) {
            return List.of();
        }

        @Override
        public List<Daily> daily(LocalDate from, LocalDate to) {
            lastDailyRange[0] = from;
            lastDailyRange[1] = to;
            dailyRows.add(new Daily(from, 1, 1, 1, 0));
            return dailyRows;
        }
    }
}
