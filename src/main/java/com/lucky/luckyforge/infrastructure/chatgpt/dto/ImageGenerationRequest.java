package com.lucky.luckyforge.infrastructure.chatgpt.dto;

/**
 * chatgpt2api 出图请求（POST /v1/images/generations，模型 gpt-image-2）。
 *
 * @param model  模型名（如 gpt-image-2）
 * @param prompt 出图提示词
 * @param n      生成数量
 * @param size   图尺寸（如 1080x1920）
 */
public record ImageGenerationRequest(
        String model,
        String prompt,
        Integer n,
        String size
) {
}