package com.lucky.luckyforge.application.styleanalysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 结构化风格特征（lf_style.style_json 的 Java 承载）。
 * <p>四键松散结构，对齐设计文档决策：色调/构图/主题/氛围。
 * 后续 PromptBuilder 模块可在此基础上收敛 schema。
 *
 * @param palette     色调特征（如"暖色调，以橙红为主"）
 * @param composition 构图特征（如"中心对称，留白充足"）
 * @param subject     主题特征（如"日落山景"）
 * @param mood        氛围特征（如"宁静温暖"）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StyleFeatures(
        String palette,
        String composition,
        String subject,
        String mood
) {
}
