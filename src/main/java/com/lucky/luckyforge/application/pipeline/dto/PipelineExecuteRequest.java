package com.lucky.luckyforge.application.pipeline.dto;

/**
 * 流水线执行请求（可选指定生成张数）。
 *
 * @param count 生成张数（可空：空则用 batch.targetCount；范围 1-12）
 */
public record PipelineExecuteRequest(
        Integer count
) {
    public PipelineExecuteRequest {
        if (count != null && (count < 1 || count > 12)) {
            throw new IllegalArgumentException("count 范围 1-12");
        }
    }
}
