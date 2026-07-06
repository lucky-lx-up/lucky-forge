package com.lucky.luckyforge.application.styleanalysis;

import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;

/**
 * 风格提炼服务。
 * <p>读取批次参考图，调 gpt-5.5 多模态提炼风格特征，写 lf_style 并回填 batch.styleId。
 */
public interface StyleAnalysisService {

    /**
     * 对指定批次执行风格提炼。
     *
     * @param batchId 批次 id（必须存在且含参考图）
     * @return 风格提炼响应（含新 style id 与特征）
     */
    StyleAnalysisResponse analyze(Long batchId);
}
