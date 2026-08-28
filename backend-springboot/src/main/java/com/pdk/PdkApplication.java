package com.pdk;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 拼多多云控商业化平台 - Spring Boot 3 主启动类
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
@MapperScan({"com.pdk.mapper", "com.pdk.business.zhibo.live.mapper"})
public class PdkApplication {
    public static void main(String[] args) {
        SpringApplication.run(PdkApplication.class, args);
    }
}
