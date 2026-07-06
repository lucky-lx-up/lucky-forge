package com.lucky.luckyforge.application.pipeline;

import com.lucky.luckyforge.application.pipeline.dto.PipelineResult;

/**
 * 流水线编排服务。
 * <p>复用 5 个单环 Service，串联执行：风格提炼→提示词→出图→打分→打包。
 */
public interface PipelineOrchestratorService {

    /**
     * 对指定 batch 执行全流程。
     *
     * @param batchId 批次 id（必须已含参考图）
     * @return 全流程聚合结果
     */
    PipelineResult execute(Long batchId);
}
