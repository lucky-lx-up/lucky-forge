package com.lucky.luckyforge.application.pipeline;

import com.lucky.luckyforge.application.pipeline.dto.PipelineResult;
import com.lucky.luckyforge.application.pipeline.dto.PipelineStatusResponse;

/**
 * 流水线编排服务。
 * <p>复用 5 个单环 Service，串联执行：风格提炼→提示词→出图→打分→打包。
 * 支持同步 execute（阻塞到完成）和异步 executeAsync（立即返回，后台执行 + 轮询进度）。
 */
public interface PipelineOrchestratorService {

    /**
     * 对指定 batch 同步执行全流程（阻塞到完成，约 2-5 分钟）。
     *
     * @param batchId 批次 id（必须已含参考图）
     * @return 全流程聚合结果
     */
    PipelineResult execute(Long batchId);

    /**
     * 异步执行全流程：立即返回 runId，后台虚拟线程执行，通过 getPipelineStatus 轮询进度。
     *
     * @param batchId 批次 id（必须已含参考图）
     * @return 后台执行用的 runId（用于轮询）
     */
    Long executeAsync(Long batchId);

    /**
     * 查询异步流水线的执行状态（步骤进度 + 最终结果）。
     *
     * @param runId executeAsync 返回的 runId
     * @return 当前状态（运行中/已完成/失败 + 各步明细）
     */
    PipelineStatusResponse getPipelineStatus(Long runId);
}
