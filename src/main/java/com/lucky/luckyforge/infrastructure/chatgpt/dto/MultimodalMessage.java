package com.lucky.luckyforge.infrastructure.chatgpt.dto;

import java.util.List;

/**
 * 多模态对话消息（OpenAI chat completions 多模态协议）。
 * <p>对应请求体 {@code messages[]} 单条消息：{@code {role:"user", content:[ContentPart...]}}。
 * 与纯文本 {@link ChatMessage}（content 为 String）不同，本类的 content 为部件数组，
 * 允许在同一条消息中混合文本与图片。
 *
 * <p>典型用法（风格提炼）：用户消息含一段文字指令 + N 张参考图的 image_url。
 *
 * @param role    角色：system / user / assistant
 * @param content 内容部件列表（不能为空）
 */
public record MultimodalMessage(
        String role,
        List<ContentPart> content
) {

    /**
     * 紧凑构造器：校验字段非空。
     */
    public MultimodalMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("MultimodalMessage 的 role 不能为空");
        }
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("MultimodalMessage 的 content 不能为空");
        }
    }

    /**
     * 构造用户消息（最常见场景）。
     *
     * @param parts 内容部件
     * @return role=user 的消息
     */
    public static MultimodalMessage user(List<ContentPart> parts) {
        return new MultimodalMessage("user", parts);
    }

    /**
     * 构造系统消息。
     *
     * @param parts 内容部件
     * @return role=system 的消息
     */
    public static MultimodalMessage system(List<ContentPart> parts) {
        return new MultimodalMessage("system", parts);
    }
}
