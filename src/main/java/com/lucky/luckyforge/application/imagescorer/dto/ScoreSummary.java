package com.lucky.luckyforge.application.imagescorer.dto;

import java.util.List;

/**
 * 一次打分任务的汇总结果。
 *
 * @param runId     所属运行 id
 * @param total     生成图总数
 * @param succeeded 成功数
 * @param failed    失败数
 * @param topN      Top N 阈值（batch.targetCount）
 * @param results   每张图的打分结果（按 total 降序，失败的排末尾）
 */
public record ScoreSummary(
        Long runId,
        int total,
        int succeeded,
        int failed,
        int topN,
        List<ScoreResult> results
) {

    /**
     * 紧凑构造器：校验 runId 与结果列表非空。
     */
    public ScoreSummary {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 非法");
        }
        if (results == null) {
            throw new IllegalArgumentException("results 不能为 null");
        }
    }
}
