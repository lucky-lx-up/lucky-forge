package com.lucky.luckyforge.infrastructure.chatgpt.dto;

import java.util.List;

/**
 * chatgpt2api 文字补全请求（POST /v1/chat/completions，模型 gpt-5.5）。
 * <p>用于风格提炼、打分、文案生成等文字分析场景。字段对齐 chatgpt2api 接口契约。
 *
 * @param model    模型名（如 gpt-5.5）
 * @param messages 对话消息列表
 * @param temperature 采样温度，控制创造性
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature
) {
    /** 便捷构造：单条用户消息 */
    public static ChatCompletionRequest of(String model, String systemPrompt, String userContent) {
        var sys = systemPrompt == null ? null : new ChatMessage("system", systemPrompt);
        var user = new ChatMessage("user", userContent);
        List<ChatMessage> msgs = sys == null ? List.of(user) : List.of(sys, user);
        return new ChatCompletionRequest(model, msgs, null);
    }
}