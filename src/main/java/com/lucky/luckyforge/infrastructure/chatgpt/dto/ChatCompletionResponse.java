package com.lucky.luckyforge.infrastructure.chatgpt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * chatgpt2api 文字补全响应。
 * <p>忽略未知字段，便于在接口细节调整时不反序列化失败。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(
        List<Choice> choices
) {
    /** 取首个选项的文本内容 */
    public String firstContent() {
        if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
            return null;
        }
        return choices.get(0).message().content();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(ChatMessage message) {
    }
}