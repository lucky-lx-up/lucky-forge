package com.lucky.luckyforge.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 * <p>装配分页拦截器（MySQL 方言）；逻辑删除由 application.yaml 的全局配置统一管理
 * （logic-delete-field: deletedAt），含该字段的实体自动生效，无需逐实体标注。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件：为后续流水线模块的分页查询提供支持。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}