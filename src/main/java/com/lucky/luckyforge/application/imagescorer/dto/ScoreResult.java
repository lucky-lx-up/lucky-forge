package com.lucky.luckyforge.application.imagescorer.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单张生成图的打分结果。
 *
 * @param generatedImageId 对应的 lf_generated_image id
 * @param scoreId          成功时为 lf_score 新记录 id；失败时为 null
 * @param total            总分（0-100）；失败时为 null
 * @param remark           gpt-5.5 整体评语；失败时为 null
 * @param dimensions       维度分明细；失败时为 null
 * @param success          是否打分成功
 * @param errorMessage     失败时的错误信息；成功时为 null
 * @param topN             是否入选 Top N（按 total 降序前 batch.targetCount 个）
 */
public record ScoreResult(
        Long generatedImageId,
        Long scoreId,
        BigDecimal total,
        String remark,
        List<DimensionScore> dimensions,
        boolean success,
        String errorMessage,
        boolean topN
) {
}
