package com.lucky.luckyforge.application.imagescorer.dto;

import java.math.BigDecimal;

/**
 * 单个维度的得分。
 *
 * @param name  维度名：composition / color / clarity / relevance
 * @param value 得分（0-100）
 */
public record DimensionScore(
        String name,
        BigDecimal value
) {
}
