package com.lucky.luckyforge.application.pipeline.dto;

/**
 * 单个流水线步骤的执行结果。
 *
 * @param step        步骤名：STYLE / PROMPT / GENERATE / SCORE / PACKAGE
 * @param success     是否成功
 * @param elapsedMs   耗时（毫秒）
 * @param detail      关键产出描述（如 "styleId=10"、"succeeded=2/failed=1"）
 * @param errorMessage 失败时的错误信息；成功时为 null
 */
public record PipelineStepResult(
        String step,
        boolean success,
        long elapsedMs,
        String detail,
        String errorMessage
) {
}
