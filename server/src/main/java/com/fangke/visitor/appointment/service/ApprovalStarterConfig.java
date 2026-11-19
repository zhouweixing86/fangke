package com.fangke.visitor.appointment.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M5 未接入时的默认审批启动器（空实现）。
 */
@Configuration
public class ApprovalStarterConfig {

    @Bean
    @ConditionalOnMissingBean(ApprovalStarter.class)
    public ApprovalStarter defaultApprovalStarter() {
        return ApprovalStarter.noop();
    }
}
