package com.lucky.luckyforge.application.styleanalysis.dto;

/**
 * 风格提炼响应 DTO。
 * <p>承载 StyleAnalyzer 产出的风格特征与落库结果。
 *
 * @param styleId    新建的 lf_style 记录主键
 * @param name       风格名称（gpt-5.5 生成）
 * @param description 风格自然语言描述（色调/构图/主题/氛围综述）
 * @param styleJson  结构化风格特征 JSON 字符串
 * @param batchId    触发本次提炼的批次 id（已被回填 styleId）
 */
public record StyleAnalysisResponse(
        Long styleId,
        String name,
        String description,
        String styleJson,
        Long batchId
) {

    /**
     * 紧凑构造器：校验关键字段非空。
     */
    public StyleAnalysisResponse {
        if (styleId == null || styleId <= 0) {
            throw new IllegalArgumentException("styleId 非法");
        }
        if (batchId == null || batchId <= 0) {
            throw new IllegalArgumentException("batchId 非法");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("风格名称不能为空");
        }
    }
}
