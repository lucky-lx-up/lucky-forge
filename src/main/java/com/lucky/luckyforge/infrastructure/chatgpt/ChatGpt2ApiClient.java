package com.lucky.luckyforge.infrastructure.chatgpt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.luckyforge.common.exception.ChatGptApiException;
import com.lucky.luckyforge.config.ChatGpt2ApiProperties;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.ChatCompletionRequest;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.ChatCompletionResponse;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.ImageGenerationRequest;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.ImageGenerationResponse;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.MultimodalChatCompletionRequest;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.MultimodalMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * chatgpt2api 统一客户端。
 * <p>对内暴露 {@link #chatCompletion}（gpt-5.5 文字分析/打分/文案）与
 * {@link #generateImage}（gpt-image-2 出图）两组方法，共用同一 baseUrl/apiKey 配置。
 *
 * <p>重试策略封装在客户端层：对连接超时、读超时、HTTP 5xx 执行指数退避重试
 * （初始 1s、倍数 2，次数由配置 max-retries 控制）；HTTP 4xx 不重试；
 * 重试耗尽或不可重试时抛出 {@link ChatGptApiException}。
 *
 * <p>设计要点：上层模块"调一次、拿结果，失败即异常"，不感知底层抖动；
 * 真遇业务级重试（如换提示词重试），由模块层自包 try-catch 决策。
 */
@Component
public class ChatGpt2ApiClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatGpt2ApiClient.class);

    /** 文字分析默认模型（gpt-5.5） */
    public static final String MODEL_CHAT = "gpt-5.5";
    /** 出图默认模型（gpt-image-2） */
    public static final String MODEL_IMAGE = "gpt-image-2";

    private final ChatGpt2ApiProperties properties;
    private final ObjectMapper objectMapper;

    public ChatGpt2ApiClient(ChatGpt2ApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 文字补全（gpt-5.5）：分析/打分/文案等场景。
     *
     * @param systemPrompt 系统提示词（可为空）
     * @param userContent  用户输入内容
     * @return 模型返回的首条文本
     */
    public String chatCompletion(String systemPrompt, String userContent) {
        ChatCompletionRequest body = ChatCompletionRequest.of(MODEL_CHAT, systemPrompt, userContent);
        ChatCompletionResponse resp = doPost("/v1/chat/completions", body,
                ChatCompletionResponse.class, properties.getChatTimeoutSeconds());
        return resp.firstContent();
    }

    /**
     * 多模态补全（gpt-5.5）：含图片的调用场景，如风格提炼、视觉打分。
     * <p>与纯文本 {@link #chatCompletion(String, String)} 共用同一 endpoint、模型与重试逻辑，
     * 差别仅在请求体的 messages 含 {@code image_url} 类型内容部件。
     *
     * @param systemPrompt 系统提示词（可为空，常用于约束输出格式）
     * @param messages     多模态消息列表（用户消息可含文字 + 图片 URL）
     * @return 模型返回的首条文本
     */
    public String chatCompletion(String systemPrompt, List<MultimodalMessage> messages) {
        MultimodalChatCompletionRequest body = MultimodalChatCompletionRequest.of(
                MODEL_CHAT, systemPrompt, messages);
        ChatCompletionResponse resp = doPost("/v1/chat/completions", body,
                ChatCompletionResponse.class, properties.getChatTimeoutSeconds());
        return resp.firstContent();
    }

    /**
     * 出图（gpt-image-2）：返回首张图的 Base64 数据。
     *
     * @param prompt 出图提示词
     * @param size   图尺寸（如 1080x1920）
     * @return Base64 编码的图片数据
     */
    public String generateImage(String prompt, String size) {
        ImageGenerationRequest body = new ImageGenerationRequest(MODEL_IMAGE, prompt, 1, size);
        ImageGenerationResponse resp = doPost("/v1/images/generations", body,
                ImageGenerationResponse.class, properties.getImageTimeoutSeconds());
        return resp.firstBase64();
    }

    /**
     * 执行带重试的 POST 请求。
     *
     * @param path             接口路径（拼接到 baseUrl）
     * @param body             请求体
     * @param responseType     响应类型
     * @param timeoutSeconds   单次请求超时（秒）
     */
    private <T> T doPost(String path, Object body, Class<T> responseType, int timeoutSeconds) {
        String url = properties.getBaseUrl() + path;
        int maxRetries = Math.max(0, properties.getMaxRetries());

        Exception lastException = null;
        int lastStatus = -1;
        String lastBody = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 非首次尝试前做指数退避等待（1s、2s、4s...）
            if (attempt > 0) {
                long backoffMs = (1L << (attempt - 1)) * 1000L;
                sleep(backoffMs);
            }
            try {
                RestClient client = buildClient(timeoutSeconds);
                T result = client.post()
                        .uri(url)
                        .header("Authorization", "Bearer " + properties.getApiKey())
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(responseType);
                return result;
            } catch (HttpClientErrorException e) {
                // 4xx：鉴权/参数错误等，重试无意义，直接抛出
                throw new ChatGptApiException(
                        "chatgpt2api 客户端错误（不重试）: " + e.getStatusCode(),
                        e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            } catch (HttpServerErrorException e) {
                // 5xx：服务端错误，可重试
                lastStatus = e.getStatusCode().value();
                lastBody = e.getResponseBodyAsString();
                lastException = e;
                log.warn("chatgpt2api 5xx（尝试 {}/{}）: {} {}", attempt + 1, maxRetries + 1,
                        e.getStatusCode(), url);
            } catch (ResourceAccessException e) {
                // 连接超时/读超时，可重试
                lastStatus = -1;
                lastBody = null;
                lastException = e;
                log.warn("chatgpt2api 超时（尝试 {}/{}）: {}", attempt + 1, maxRetries + 1, url);
            }
        }
        throw new ChatGptApiException(
                "chatgpt2api 重试耗尽（共 " + (maxRetries + 1) + " 次）: " + url,
                lastStatus, lastBody, lastException);
    }

    /**
     * 构建一次性的 RestClient，按调用场景配置连接与读取超时 + message converter。
     *
     * <p><b>超时</b>：用 {@link org.springframework.http.client.SimpleClientHttpRequestFactory}
     * 设置 connect/read 超时。此前首版漏设超时导致挂起请求无限等待。
     *
     * <p><b>Message converter</b>：chatgpt2api 的出图响应偶发以
     * {@code application/octet-stream}（而非 {@code application/json}）作为 Content-Type 返回。
     * RestClient 默认的 converter 列表里，{@code ByteArrayHttpMessageConverter} 排在 Jackson 前面，
     * 它也支持 octet-stream——会把响应体当 {@code byte[]} 消费，导致 Jackson 没机会解析为 DTO，
     * 抛 RestClientException。
     *
     * <p>修法：替换默认 converter 列表为单个支持所有 MediaType 的 Jackson converter，
     * 确保无论服务端返回什么 Content-Type，都由 Jackson 统一反序列化。
     *
     * @param timeoutSeconds 单次请求的连接与读取超时（秒）
     */
    private RestClient buildClient(int timeoutSeconds) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        int ms = Math.max(1, timeoutSeconds) * 1000;
        factory.setConnectTimeout(ms);
        factory.setReadTimeout(ms);

        // 单个 Jackson converter 支持所有 MediaType（含 octet-stream）
        var jacksonConverter = new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                objectMapper);
        jacksonConverter.setSupportedMediaTypes(List.of(
                org.springframework.http.MediaType.APPLICATION_JSON,
                org.springframework.http.MediaType.APPLICATION_OCTET_STREAM,
                org.springframework.http.MediaType.ALL));

        // 替换（而非追加）默认 converter 列表，避免 ByteArrayHttpMessageConverter 抢先
        return RestClient.builder()
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(jacksonConverter);
                })
                .requestFactory(factory)
                .build();
    }

    /** 不可中断的退避等待（忽略中断，保证重试节奏） */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}