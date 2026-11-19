package com.fangke.visitor.job.service;

import com.fangke.visitor.approval.repository.ApprovalTaskRepository;
import com.fangke.visitor.approval.service.ApprovalService;
import com.fangke.visitor.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批超时自动催办（M12）：把"忘了批"的待办按配置节奏提醒审批人。
 *
 * <p>复用 {@link ApprovalService#remind} 的限流（间隔 + 次数上限），
 * 所以自动催办不会绕过人工催办的规则，也不会刷屏。
 */
@Service
public class ApprovalReminderService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalReminderService.class);
    /** 系统自动操作用 0 表示，审计里能区分人工催办。 */
    private static final long SYSTEM_OPERATOR = 0L;

    private final ApprovalTaskRepository taskRepository;
    private final ApprovalService approvalService;

    public ApprovalReminderService(ApprovalTaskRepository taskRepository,
                                   ApprovalService approvalService) {
        this.taskRepository = taskRepository;
        this.approvalService = approvalService;
    }

    public int remindStale(int staleMinutes, int intervalMinutes, int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = taskRepository.findStalePending(
                now.minusMinutes(Math.max(10, staleMinutes)),
                now.minusMinutes(Math.max(5, intervalMinutes)),
                Math.max(1, limit));
        int reminded = 0;
        for (Long taskId : ids) {
            try {
                approvalService.remind(taskId, SYSTEM_OPERATOR);
                reminded++;
            } catch (BusinessException e) {
                // 已达到催办上限或刚催过：跳过，不算失败
                log.debug("跳过自动催办 task={}：{}", taskId, e.getMessage());
            } catch (Exception e) {
                log.warn("自动催办失败 task={}：{}", taskId, e.getMessage());
            }
        }
        if (reminded > 0) {
            log.info("自动催办 {} 个待办任务", reminded);
        }
        return reminded;
    }
}
