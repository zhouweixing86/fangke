package com.fangke.visitor.approval.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M5 的可插拔点默认实现：通知与授权下发在后续阶段接入。
 */
@Configuration
public class ApprovalSupportConfig {

    @Bean
    @ConditionalOnMissingBean(NotifyPort.class)
    public NotifyPort defaultNotifyPort() {
        return NotifyPort.noop();
    }

    @Bean
    @ConditionalOnMissingBean(DispatchTrigger.class)
    public DispatchTrigger defaultDispatchTrigger() {
        return DispatchTrigger.noop();
    }
}
