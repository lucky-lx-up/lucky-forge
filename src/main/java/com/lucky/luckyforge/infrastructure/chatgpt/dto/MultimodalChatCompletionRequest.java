package com.lucky.luckyforge.infrastructure.chatgpt.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * chatgpt2api 多模态补全请求（POST /v1/chat/completions，模型 gpt-5.5）。
 * <p>与纯文本 {@link ChatCompletionRequest} 并列，承载含图片的多模态调用。
 * 两者 endpoint 与模型名相同，差别仅在 {@code messages} 的元素类型：
 * <ul>
 *   <li>{@link ChatCompletionRequest}：messages 为 {@code List<ChatMessage>}（content 为 String）</li>
 *   <li>本类：messages 为 {@code List<Object>}，可混合
 *       {@link ChatMessage}（系统提示词）与 {@link MultimodalMessage}（含图片的用户消息）</li>
 * </ul>
 *
 * <p>OpenAI 协议允许 messages 数组混合不同 content 形态，本类以此承载
 * "系统提示词（纯文本）+ 用户消息（文字 + 图片）"的典型风格提炼场景。
 *
 * @param model    模型名（如 gpt-5.5）
 * @param messages 对话消息列表（元素为 ChatMessage 或 MultimodalMessage）
 * @param temperature 采样温度，控制创造性
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MultimodalChatCompletionRequest(
        String model,
        List<Object> messages,
        Double temperature
) {

    /**
     * 多模态便捷构造：可选系统提示词 + 多模态消息列表。
     * <p>系统提示词以纯文本 {@link ChatMessage} 形式追加到消息列表头部。
     *
     * @param model         模型名（如 gpt-5.5）
     * @param systemPrompt  系统提示词（可为 null）
     * @param multimodalMsgs 多模态消息列表（不能为空）
     * @return 多模态补全请求
     */
    public static MultimodalChatCompletionRequest of(String model, String systemPrompt,
                                                     List<MultimodalMessage> multimodalMsgs) {
        if (multimodalMsgs == null || multimodalMsgs.isEmpty()) {
            throw new IllegalArgumentException("多模态消息列表不能为空");
        }
        List<Object> msgs = new ArrayList<>(multimodalMsgs.size() + 1);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(new ChatMessage("system", systemPrompt));
        }
        msgs.addAll(multimodalMsgs);
        return new MultimodalChatCompletionRequest(model, msgs, null);
    }
}
