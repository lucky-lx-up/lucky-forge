package com.lucky.luckyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * chatgpt2api 客户端连接属性。
 * <p>绑定 application.yaml 中 {@code chatgpt2api} 前缀的配置。
 */
@Data
@ConfigurationProperties(prefix = "chatgpt2api")
public class ChatGpt2ApiProperties {

    /** chatgpt2api 服务基础地址 */
    private String baseUrl;

    /** 鉴权 API Key */
    private String apiKey;

    /** 文字分析（gpt-5.5）单次请求超时（秒） */
    private int chatTimeoutSeconds = 60;

    /** 出图（gpt-image-2）单次请求超时（秒） */
    private int imageTimeoutSeconds = 120;

    /** 最大重试次数（超时/5xx 时指数退避重试） */
    private int maxRetries = 3;
}