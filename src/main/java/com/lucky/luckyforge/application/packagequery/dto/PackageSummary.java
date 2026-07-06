package com.lucky.luckyforge.application.packagequery.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 素材包摘要（列表用）。
 */
public record PackageSummary(
        Long id,
        Long batchId,
        String title,
        List<String> tags,
        String status,
        int imageCount,
        LocalDateTime createdAt
) {
}
