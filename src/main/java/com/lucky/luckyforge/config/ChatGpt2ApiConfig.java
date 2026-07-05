package com.lucky.luckyforge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * chatgpt2api 配置装配：启用 {@link ChatGpt2ApiProperties} 属性绑定。
 * <p>实际的 HTTP 客户端与重试逻辑封装在 {@code ChatGpt2ApiClient} 中，
 * 它将注入本配置绑定的属性。
 */
@Configuration
@EnableConfigurationProperties(ChatGpt2ApiProperties.class)
public class ChatGpt2ApiConfig {
}