package com.lucky.luckyforge.application.promptbuilder.dto;

/**
 * 提示词生成响应（单条）。
 *
 * @param id      lf_prompt 新记录主键
 * @param runId   所属运行 id
 * @param seq     本 run 内序号
 * @param content 提示词正文（英文，送 gpt-image-2）
 */
public record PromptGenerationResponse(
        Long id,
        Long runId,
        Integer seq,
        String content
) {

    /**
     * 紧凑构造器：校验关键字段非空。
     */
    public PromptGenerationResponse {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("prompt id 非法");
        }
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 非法");
        }
        if (seq == null || seq <= 0) {
            throw new IllegalArgumentException("seq 非法");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
    }
}
