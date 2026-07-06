package com.lucky.luckyforge.application.imagegenerator.dto;

import java.util.List;

/**
 * 一次出图任务的汇总结果。
 *
 * @param runId     所属运行 id
 * @param total     提示词总数
 * @param succeeded 成功数
 * @param failed    失败数
 * @param results   每条提示词的结果明细
 */
public record ImageGenerationSummary(
        Long runId,
        int total,
        int succeeded,
        int failed,
        List<ImageGenerationResult> results
) {

    /**
     * 紧凑构造器：校验 runId 与结果列表非空。
     */
    public ImageGenerationSummary {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 非法");
        }
        if (results == null) {
            throw new IllegalArgumentException("results 不能为 null");
        }
    }
}
