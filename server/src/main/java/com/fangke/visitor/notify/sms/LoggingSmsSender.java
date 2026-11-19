package com.fangke.visitor.notify.sms;

import com.fangke.visitor.security.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日志短信通道：不真正发短信，只把内容写进日志，便于联调与验收。
 *
 * <p>接入真实通道前，此实现让"短信通知"链路可以完整跑通（落库、状态、重试都有）；
 * 接入后替换为 {@code AliyunSmsSender}，业务代码零改动。
 */
@Component
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public boolean send(String phone, String content) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        log.info("【短信·日志通道】收件人={} 内容={}", CryptoService.maskPhone(phone), content);
        return true;
    }

    @Override
    public String provider() {
        return "log";
    }
}
