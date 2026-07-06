package com.lucky.luckyforge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LuckyForge 启动类。
 * <p>扫描数据访问层 Mapper 接口；基础设施（chatgpt2api 客户端、MinIO 服务）由 config 包装配。
 * 启用定时任务（@Scheduled），用于自动清理超时的 pipeline run。
 */
@SpringBootApplication
@MapperScan("com.lucky.luckyforge.infrastructure.persistence.mapper")
@EnableScheduling
public class LuckyForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LuckyForgeApplication.class, args);
    }

}