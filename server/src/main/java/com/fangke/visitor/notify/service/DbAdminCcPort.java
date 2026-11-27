package com.fangke.visitor.notify.service;

import com.fangke.visitor.admin.repository.AdminUserRepository;
import com.fangke.visitor.approval.service.CcPort;
import com.fangke.visitor.notify.model.NotifyTemplates;
import com.fangke.visitor.wxbind.repository.WxBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审批抄送 → 消息中心（站内通知）。
 *
 * <p>收件人两类：后台账号（{@code notification.receiver_type=3}，按角色展开）与
 * 已绑定微信的员工（{@code receiver_type=2}，只发站内不发短信），后者用于小程序「消息」页查看抄送；
 * 同一个预约的同一节点对同一收件人只发一次（dedup_key 兜底，重复触发不会刷屏）。
 */
@Service
public class DbAdminCcPort implements CcPort {

    private static final Logger log = LoggerFactory.getLogger(DbAdminCcPort.class);

    private final AdminUserRepository adminUserRepository;
    private final WxBindingRepository wxBindingRepository;
    private final NotifyService notifyService;

    public DbAdminCcPort(AdminUserRepository adminUserRepository,
                         WxBindingRepository wxBindingRepository,
                         NotifyService notifyService) {
        this.adminUserRepository = adminUserRepository;
        this.wxBindingRepository = wxBindingRepository;
        this.notifyService = notifyService;
    }

    @Override
    public void ccToRole(String roleCode, Long appointmentId, String appointmentNo, String visitorName,
                         String timeRange, String stage) {
        if (roleCode == null || roleCode.isBlank()) {
            return;
        }
        NotifyTemplates.Content content =
                NotifyTemplates.approvalCc(visitorName, appointmentNo, timeRange, stage);
        String dedup = "cc:" + roleCode + ":" + stage;
        List<Long> userIds = adminUserRepository.findActiveUserIdsByRole(roleCode);
        int sent = 0;
        for (Long userId : userIds) {
            boolean inserted = notifyService.notifyAdmin(userId, NotifyTemplates.APPROVAL_CC, content,
                    "APPOINTMENT", appointmentNo, dedup);
            if (inserted) {
                sent++;
            }
        }
        // 小程序员工端：同角色且已绑定微信的员工，只发站内消息（不额外占短信额度）
        List<Long> employeeIds = wxBindingRepository.findEmployeeIdsByRole(roleCode);
        int sentApp = 0;
        for (Long employeeId : employeeIds) {
            boolean inserted = notifyService.notifyEmployee(employeeId, NotifyTemplates.APPROVAL_CC,
                    content, "APPOINTMENT", appointmentNo, dedup, false);
            if (inserted) {
                sentApp++;
            }
        }
        if (userIds.isEmpty() && employeeIds.isEmpty()) {
            log.warn("抄送跳过：角色 {} 下没有启用账号或已绑定微信的员工（预约 {}）", roleCode, appointmentNo);
            return;
        }
        log.info("审批抄送 预约={} 角色={} 阶段={} 后台账号={} 新增={} 小程序员工={} 新增={}",
                appointmentNo, roleCode, stage, userIds.size(), sent, employeeIds.size(), sentApp);
    }
}
