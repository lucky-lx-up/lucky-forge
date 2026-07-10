package com.lucky.luckyforge.application.promptlibrary.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提示词库出图历史摘要（列表用，不含每条 prompt 明细）。
 *
 * <p>对应一次库出图（一个 run + 其占位 batch），前端「出图历史」标签页逐条展示，
 * 点击后跳转 {@code /prompts/run/{runId}} 查看完整结果（复用 {@link LibraryRunDetail}）。
 *
 * @param runId           运行 id（跳转结果页用）
 * @param status          运行状态：PENDING/RUNNING/SUCCESS/FAILED
 * @param styleId         所用风格 id
 * @param styleName       所用风格名称（前端展示）
 * @param vertical        垂类
 * @param promptCount     本次出图的提示词条数（= batch.targetCount）
 * @param firstObjectKey  首图（seq 最小者）MinIO 路径；出图全失败则为空
 * @param firstPreviewUrl 首图预签名预览 URL（后端签好，前端零额外请求直显）；失败则为空
 * @param avgScore        平均总分（已打分提示词的总分均值）；未打分或全失败则为空
 * @param startedAt       开始时间
 * @param finishedAt      结束时间（运行中则为空）
 */
public record LibraryRunSummary(
        Long runId,
        String status,
        Long styleId,
        String styleName,
        String vertical,
        Integer promptCount,
        String firstObjectKey,
        String firstPreviewUrl,
        BigDecimal avgScore,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    /**
     * 紧凑构造器：校验关键字段。
     */
    public LibraryRunSummary {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 非法");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (promptCount == null || promptCount < 0) {
            promptCount = 0;
        }
    }
}
