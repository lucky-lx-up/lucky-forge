package com.lucky.luckyforge.application.batch.dto;

import java.time.LocalDateTime;

/**
 * 批次摘要（列表/详情共用）。
 *
 * @param id          批次 id
 * @param theme       主题
 * @param vertical    垂类
 * @param targetCount 目标出图数
 * @param status      状态：DRAFT/RUNNING/DONE/FAILED
 * @param styleId     风格 id（可空，未提炼时为 null）
 * @param createdAt   创建时间
 */
public record BatchSummary(
        Long id,
        String theme,
        String vertical,
        Integer targetCount,
        String status,
        Long styleId,
        LocalDateTime createdAt
) {
}
