package com.fangke.visitor.notify.sms;

/**
 * 短信发送出口。当前只有日志实现（开发/未采购通道），接入阿里云/腾讯云时新增实现并注入即可。
 */
public interface SmsSender {

    /**
     * @return true 表示已提交给通道（不代表已送达）
     */
    boolean send(String phone, String content);

    String provider();
}
