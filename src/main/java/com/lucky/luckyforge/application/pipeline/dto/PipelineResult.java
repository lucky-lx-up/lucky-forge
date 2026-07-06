package com.lucky.luckyforge.application.pipeline.dto;

import java.util.List;

/**
 * 流水线全流程聚合结果。
 *
 * @param batchId        批次 id
 * @param runId          运行 id（PromptBuilder 创建）
 * @param styleId        风格 id（StyleAnalyzer 产出；失败时为 null）
 * @param packageId      素材包 id（PackageAssembler 产出；失败时为 null）
 * @param overallSuccess 整体是否成功
 * @param overallMessage 整体信息（成功/失败摘要）
 * @param steps          每步的执行明细
 */
public record PipelineResult(
        Long batchId,
        Long runId,
        Long styleId,
        Long packageId,
        boolean overallSuccess,
        String overallMessage,
        List<PipelineStepResult> steps
) {

    /**
     * 紧凑构造器：校验关键字段。
     */
    public PipelineResult {
        if (batchId == null || batchId <= 0) {
            throw new IllegalArgumentException("batchId 非法");
        }
        if (steps == null) {
            steps = List.of();
        }
    }
}
