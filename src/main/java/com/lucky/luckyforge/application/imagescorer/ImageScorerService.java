package com.lucky.luckyforge.application.imagescorer;

import com.lucky.luckyforge.application.imagescorer.dto.ScoreSummary;

/**
 * 自动打分服务（流水线第④环）。
 * <p>对 run 下所有生成图按 4 维度打分，覆盖式落库，返回汇总 + Top N 标识。
 */
public interface ImageScorerService {

    /**
     * 对指定 run 下所有生成图触发打分。
     *
     * @param runId 运行 id（必须含 lf_generated_image）
     * @return 打分汇总（含每张成功/失败明细 + TopN 标识）
     */
    ScoreSummary scoreImages(Long runId);
}
