package com.lucky.luckyforge.common.exception;

import lombok.Getter;

/**
 * chatgpt2api 调用最终失败异常。
 * <p>当 {@code ChatGpt2ApiClient} 重试耗尽（超时/5xx）或遇到不可重试的 4xx 时抛出，
 * 携带最后一次响应信息，便于上层定位与排查。
 */
@Getter
public class ChatGptApiException extends RuntimeException {

    /** 最后一次响应的状态码（连接失败时为 -1） */
    private final int statusCode;

    /** 最后一次响应体（可为空） */
    private final String responseBody;

    public ChatGptApiException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }
}
