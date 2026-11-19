package com.fangke.visitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 访客管理系统服务端入口。
 *
 * <p>P1 范围：M1 基础框架 + M3 设备中心（局域网通道优先）。
 * 接口契约见 docs/11-接口详细契约.md，数据模型见 docs/10-数据库设计.md。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FangkeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FangkeApplication.class, args);
    }
}
