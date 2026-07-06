package com.lucky.luckyforge.application.batch.dto;

import java.time.LocalDateTime;

/**
 * 批次详情（含运行状态）。
 *
 * @param id          批次 id
 * @param theme       主题
 * @param vertical    垂类
 * @param targetCount 目标出图数
 * @param status      批次状态
 * @param styleId     风格 id（可空）
 * @param runId       最近运行的 id（可空）
 * @param runStatus   最近运行的状态（可空）
 * @param runCurrentStep 最近运行的当前步骤（可空）
 * @param createdAt   创建时间
 */
public record BatchDetail(
        Long id,
        String theme,
        String vertical,
        Integer targetCount,
        String status,
        Long styleId,
        Long runId,
        String runStatus,
        String runCurrentStep,
        LocalDateTime createdAt
) {
}
