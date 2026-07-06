package com.lucky.luckyforge.application.packageassembler.dto;

import java.math.BigDecimal;

/**
 * 素材包内的单张图片项。
 *
 * @param generatedImageId 生成图 id
 * @param objectKey        MinIO 对象路径
 * @param sortOrder        包内排序（0 为封面）
 * @param score            该图的打分总分（便于前端展示质量）
 */
public record PackageImageItem(
        Long generatedImageId,
        String objectKey,
        Integer sortOrder,
        BigDecimal score
) {
}
