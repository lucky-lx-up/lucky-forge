package com.lucky.luckyforge.application.pipeline.dto;

/**
 * 异步流水线的执行状态（轮询用）。
 *
 * @param runId          运行 id
 * @param status         整体状态：IDLE（无流水线执行记录）/ RUNNING / SUCCESS / FAILED
 * @param currentStep    当前正在执行的步骤（RUNNING 时有值）：STYLE/PROMPT/GENERATE/SCORE/PACKAGE
 * @param overallMessage 完成时的信息（成功/失败摘要）
 * @param packageId      最终产出的素材包 id（完成时有值）
 * @param steps          各步执行明细
 */
public record PipelineStatusResponse(
        Long runId,
        String status,
        String currentStep,
        String overallMessage,
        Long packageId,
        java.util.List<PipelineStepResult> steps
) {
}
