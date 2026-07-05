package com.lucky.luckyforge.infrastructure.chatgpt.dto;

/**
 * chatgpt2api 对话消息。
 *
 * @param role    角色：system / user / assistant
 * @param content 内容
 */
public record ChatMessage(String role, String content) {
}