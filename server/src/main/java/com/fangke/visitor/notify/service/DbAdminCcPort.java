package com.fangke.visitor.notify.service;

import com.fangke.visitor.admin.repository.AdminUserRepository;
import com.fangke.visitor.approval.service.CcPort;
import com.fangke.visitor.notify.model.NotifyTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审批抄送 → 管理端消息中心（站内通知）。
 *
 * <p>收件人是**后台账号**（{@code notification.receiver_type=3}），按角色展开成具体账号；
 * 同一个预约的同一节点只发一次（dedup_key 兜底，重复触发不会刷屏）。
 */
@Service
public class DbAdminCcPort implements CcPort {

    private static final Logger log = LoggerFactory.getLogger(DbAdminCcPort.class);

    private final AdminUserRepository adminUserRepository;
    private final NotifyService notifyService;

    public DbAdminCcPort(AdminUserRepository adminUserRepository, NotifyService notifyService) {
        this.adminUserRepository = adminUserRepository;
        this.notifyService = notifyService;
    }

    @Override
    public void ccToRole(String roleCode, Long appointmentId, String appointmentNo, String visitorName,
                         String timeRange, String stage) {
        if (roleCode == null || roleCode.isBlank()) {
            return;
        }
        List<Long> userIds = adminUserRepository.findActiveUserIdsByRole(roleCode);
        if (userIds.isEmpty()) {
            log.warn("抄送跳过：角色 {} 下没有启用账号（预约 {}）", roleCode, appointmentNo);
            return;
        }
        NotifyTemplates.Content content =
                NotifyTemplates.approvalCc(visitorName, appointmentNo, timeRange, stage);
        int sent = 0;
        for (Long userId : userIds) {
            boolean inserted = notifyService.notifyAdmin(userId, NotifyTemplates.APPROVAL_CC, content,
                    "APPOINTMENT", appointmentNo, "cc:" + roleCode + ":" + stage);
            if (inserted) {
                sent++;
            }
        }
        log.info("审批抄送 预约={} 角色={} 阶段={} 收件人={} 新增={}",
                appointmentNo, roleCode, stage, userIds.size(), sent);
    }
}
