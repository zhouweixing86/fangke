package com.fangke.visitor.job.service;

import com.fangke.visitor.approval.repository.ApprovalTaskRepository;
import com.fangke.visitor.approval.service.ApprovalService;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批超时自动催办：命中超时任务、达到上限时跳过不影响其它任务。
 */
class ApprovalReminderServiceTest {

    private final ApprovalTaskRepository taskRepo = mock(ApprovalTaskRepository.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final ApprovalReminderService service = new ApprovalReminderService(taskRepo, approvalService);

    @Test
    @DisplayName("超时未处理任务：逐个调用系统催办")
    void remindsStaleTasks() {
        when(taskRepo.findStalePending(any(), any(), anyInt())).thenReturn(List.of(11L, 12L));
        when(approvalService.remind(anyLong(), eq(0L))).thenReturn(2);

        int reminded = service.remindStale(120, 30, 100);

        assertEquals(2, reminded);
        verify(approvalService).remind(11L, 0L);
        verify(approvalService).remind(12L, 0L);
    }

    @Test
    @DisplayName("达到催办上限的任务：跳过，不抛异常")
    void skipsTaskOverLimit() {
        when(taskRepo.findStalePending(any(), any(), anyInt())).thenReturn(List.of(21L, 22L));
        when(approvalService.remind(21L, 0L))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该任务最多催办 2 次"));
        when(approvalService.remind(22L, 0L)).thenReturn(1);

        int reminded = service.remindStale(120, 30, 100);

        assertEquals(1, reminded);
    }

    @Test
    @DisplayName("没有超时任务：不调用催办")
    void noStaleTasks() {
        when(taskRepo.findStalePending(any(), any(), anyInt())).thenReturn(List.of());

        assertEquals(0, service.remindStale(120, 30, 100));
    }
}
