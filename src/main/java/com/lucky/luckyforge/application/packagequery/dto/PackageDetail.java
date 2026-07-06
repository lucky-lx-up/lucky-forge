package com.lucky.luckyforge.application.packagequery.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 素材包详情（含图片列表 + 预览 URL）。
 */
public record PackageDetail(
        Long id,
        Long batchId,
        String title,
        List<String> tags,
        String status,
        List<PackageImageDetail> images,
        LocalDateTime createdAt
) {
}
