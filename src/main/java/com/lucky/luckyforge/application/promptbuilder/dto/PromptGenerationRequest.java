package com.lucky.luckyforge.application.promptbuilder.dto;

/**
 * 提示词生成请求。
 *
 * @param count 期望生成的提示词数量（可空：空则用 batch.targetCount；上限 12）
 */
public record PromptGenerationRequest(
        Integer count
) {
    /** 提示词数量上限（防滥用） */
    public static final int MAX_COUNT = 12;

    /**
     * 紧凑构造器：校验 count 合法性（null 允许，由 Service 兜底用 targetCount）。
     */
    public PromptGenerationRequest {
        if (count != null && count <= 0) {
            throw new IllegalArgumentException("count 必须为正数");
        }
        if (count != null && count > MAX_COUNT) {
            throw new IllegalArgumentException("count 超过上限 " + MAX_COUNT);
        }
    }
}
