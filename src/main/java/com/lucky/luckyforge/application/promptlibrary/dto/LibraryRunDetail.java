package com.lucky.luckyforge.application.promptlibrary.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台某次出图的详情（前端结果页用）。
 *
 * <p>聚合 run 状态 + 每条提示词的出图结果 + 打分，供前端轮询展示进度与结果。
 *
 * @param runId       运行 id
 * @param status      运行状态：PENDING/RUNNING/SUCCESS/FAILED
 * @param currentStep 当前步骤：GENERATE/SCORE（工作台只走这两步）
 * @param error       失败时的错误信息（可空）
 * @param styleId     所用风格 id
 * @param styleName   所用风格名称
 * @param vertical    垂类
 * @param prompts     每条提示词的结果明细（含出图 + 打分）
 * @param startedAt   开始时间
 * @param finishedAt  结束时间（可空）
 */
public record LibraryRunDetail(
        Long runId,
        String status,
        String currentStep,
        String error,
        Long styleId,
        String styleName,
        String vertical,
        List<PromptWithResult> prompts,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    /**
     * 紧凑构造器：校验关键字段。
     */
    public LibraryRunDetail {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 非法");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status 不能为空");
        }
    }

    /**
     * 单条提示词及其出图/打分结果。
     *
     * @param promptId        lf_prompt id
     * @param seq             run 内序号
     * @param content         提示词正文
     * @param generatedImageId 生成图 id（出图失败则为空）
     * @param objectKey       生成图 MinIO 路径（前端换预签名 URL 展示；失败则空）
     * @param score           打分总分（0-100；未打分或失败则空）
     * @param remark          打分评语（可空）
     * @param dimensions      维度分明细（可空）
     * @param archived        该 prompt 是否已归档进库（前端据此禁用归档按钮）
     */
    public record PromptWithResult(
            Long promptId,
            Integer seq,
            String content,
            Long generatedImageId,
            String objectKey,
            BigDecimal score,
            String remark,
            List<DimensionBrief> dimensions,
            boolean archived
    ) {

        /**
         * 紧凑构造器：校验关键字段。
         */
        public PromptWithResult {
            if (promptId == null || promptId <= 0) {
                throw new IllegalArgumentException("promptId 非法");
            }
            if (seq == null || seq <= 0) {
                throw new IllegalArgumentException("seq 非法");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content 不能为空");
            }
        }
    }

    /**
     * 维度分简要（与 imagescorer 的 DimensionScore 对应，独立定义避免跨模块耦合）。
     *
     * @param name  维度名
     * @param value 得分
     */
    public record DimensionBrief(
            String name,
            BigDecimal value
    ) {

        /**
         * 紧凑构造器：校验非空。
         */
        public DimensionBrief {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("维度 name 不能为空");
            }
            if (value == null) {
                throw new IllegalArgumentException("维度 value 不能为空");
            }
        }
    }
}
