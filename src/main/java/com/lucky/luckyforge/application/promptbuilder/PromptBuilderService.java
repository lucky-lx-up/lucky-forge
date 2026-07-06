package com.lucky.luckyforge.application.promptbuilder;

import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationResponse;

import java.util.List;

/**
 * 提示词生成服务（流水线第②环）。
 * <p>基于 batch 的风格特征 + 主题，调 gpt-5.5 单次生成 N 条差异化英文提示词。
 */
public interface PromptBuilderService {

    /**
     * 为指定批次生成出图提示词。
     *
     * @param batchId 批次 id（必须已含 styleId）
     * @param request 请求数量（count 为空则用 batch.targetCount）
     * @return 生成的提示词列表
     */
    List<PromptGenerationResponse> generatePrompts(Long batchId, PromptGenerationRequest request);
}
