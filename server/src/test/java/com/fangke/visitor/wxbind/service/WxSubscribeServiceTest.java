package com.fangke.visitor.wxbind.service;

import com.fangke.visitor.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订阅消息：字段截断与「未配置就跳过」的判定（docs/26 B1）。
 */
class WxSubscribeServiceTest {

    private WxSubscribeService service(AppProperties props) {
        return new WxSubscribeService(props, new WxacodeService(props), null, null);
    }

    @Test
    void 按模板字段类型截断到微信限制() {
        assertEquals(20, WxSubscribeService.truncate("thing1", "张三丰来访事由写得特别长超过二十个字符了").length());
        assertEquals(10, WxSubscribeService.truncate("name2", "欧阳锋欧阳锋欧阳锋欧阳锋").length());
        assertEquals(5, WxSubscribeService.truncate("phrase3", "已通过审批").length());
        assertEquals("A2026112700001",
                WxSubscribeService.truncate("character_string4", "A2026112700001"));
        assertEquals("2026-11-27 09:00", WxSubscribeService.truncate("time5", "2026-11-27 09:00"));
    }

    @Test
    void 没有模板ID时视为未配置_不发送() {
        AppProperties props = new AppProperties();
        props.getWx().setAppId("wx-test");
        props.getWx().setSecret("secret-test");
        assertFalse(service(props).configured());

        props.getWx().getSubscribe().setTodoTemplateId("tmpl-todo");
        assertTrue(service(props).configured());

        props.getWx().getSubscribe().setEnabled(false);
        assertFalse(service(props).configured());
    }
}